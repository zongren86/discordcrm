package com.discordadmin.translation;

import com.discordadmin.entity.AISetting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 千问AI翻译服务 - 支持通义 Qwen-MT-Plus 翻译专用模型
 */
@Service
public class QianwenTranslationService {

    private static final Logger log = LoggerFactory.getLogger(QianwenTranslationService.class);

    // 百炼兼容模式端点（推荐，支持所有模型包括 qwen-mt 系列）
    private static final String COMPATIBLE_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    // 百炼原生 API 端点（旧版）
    private static final String NATIVE_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
    
    // 支持的翻译模型列表
    public static final String MODEL_QWEN_MT_PLUS = "qwen-mt-plus";
    public static final String MODEL_QWEN_MT_TURBO = "qwen-mt-turbo";
    public static final String MODEL_QWEN_TURBO = "qwen-turbo";
    public static final String MODEL_QWEN_PLUS = "qwen-plus";
    public static final String MODEL_QWEN_MAX = "qwen-max";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 使用千问AI翻译文本
     * @param text 要翻译的文本
     * @param targetLanguage 目标语言
     * @param setting AI配置
     * @return 翻译后的文本
     */
    public Optional<String> translate(String text, String targetLanguage, AISetting setting) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        try {
            String apiKey = setting.getApiKey();
            String model = setting.getModel() != null ? setting.getModel() : MODEL_QWEN_MT_PLUS;
            String endpoint = setting.getApiEndpoint() != null ? setting.getApiEndpoint() : COMPATIBLE_ENDPOINT;

            // 构建翻译提示词
            String sourceLang = detectLanguageHint(text);
            String prompt = String.format(
                "请将以下%s文本翻译成%s，只返回翻译结果，不要添加任何解释：\n\n%s",
                sourceLang, getTargetLanguageName(targetLanguage), text);

            // 根据端点类型构建请求
            String requestBody;
            if (endpoint.contains("compatible-mode")) {
                requestBody = buildCompatibleRequestBody(model, prompt, setting);
            } else {
                requestBody = buildNativeRequestBody(model, prompt, setting);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("千问翻译API返回非200状态码: {}, body: {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            return parseResponse(response.body());

        } catch (Exception e) {
            log.error("千问翻译服务异常: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 构建兼容模式请求体（推荐）
     * POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
     */
    private String buildCompatibleRequestBody(String model, String prompt, AISetting setting) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", "你是专业的翻译助手，只输出翻译结果，不要添加任何解释或说明。");
            
            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            
            body.put("messages", new Object[]{systemMessage, userMessage});
            body.put("temperature", setting.getTemperature() != null ? setting.getTemperature() : 0.3);
            body.put("max_tokens", setting.getMaxTokens() != null ? setting.getMaxTokens() : 1024);

            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("构建兼容模式请求体失败", e);
            return String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"system\",\"content\":\"你是专业的翻译助手\"},{\"role\":\"user\",\"content\":\"%s\"}],\"temperature\":0.3}",
                model, prompt.replace("\"", "\\\"").replace("\n", "\\n"));
        }
    }

    /**
     * 构建原生模式请求体（旧版）
     * POST https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation
     */
    private String buildNativeRequestBody(String model, String prompt, AISetting setting) {
        try {
            Map<String, Object> input = new HashMap<>();
            input.put("messages", new Object[]{
                Map.of("role", "user", "content", prompt)
            });

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("temperature", setting.getTemperature() != null ? setting.getTemperature() : 0.3);
            parameters.put("max_tokens", setting.getMaxTokens() != null ? setting.getMaxTokens() : 1024);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("input", input);
            body.put("parameters", parameters);

            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("构建原生模式请求体失败", e);
            return String.format(
                "{\"model\":\"%s\",\"input\":{\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}]}}",
                model, prompt.replace("\"", "\\\"").replace("\n", "\\n"));
        }
    }

    private Optional<String> parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            
            // 尝试兼容模式响应格式: {"choices":[{"message":{"content":"..."}}]}
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).path("message");
                String content = message.path("content").asText(null);
                if (content != null && !content.isBlank()) {
                    return Optional.of(content.trim());
                }
            }

            // 尝试原生API响应格式: {"output":{"choices":[{"message":{"content":"..."}}]}}
            JsonNode output = root.path("output");
            if (!output.isMissingNode()) {
                choices = output.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode message = choices.get(0).path("message");
                    String content = message.path("content").asText(null);
                    if (content != null && !content.isBlank()) {
                        return Optional.of(content.trim());
                    }
                }
            }

            return Optional.empty();
        } catch (Exception e) {
            log.error("解析千问翻译响应失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String detectLanguageHint(String text) {
        if (text.matches(".*[\\u4e00-\\u9fa5].*")) {
            return "中文";
        } else if (text.matches(".*[\\u3040-\\u309F].*")) {
            return "日文";
        } else if (text.matches(".*[\\uAC00-\\uD7AF].*")) {
            return "韩文";
        } else {
            return "英文";
        }
    }

    private String getTargetLanguageName(String targetLanguage) {
        switch (targetLanguage.toLowerCase()) {
            case "zh-cn":
            case "zh":
                return "简体中文";
            case "en":
            case "en-us":
                return "英文";
            case "ja":
                return "日文";
            case "ko":
                return "韩文";
            default:
                return targetLanguage;
        }
    }

    /**
     * 获取支持的模型列表（用于前端下拉选择）
     */
    public static String[] getSupportedModels() {
        return new String[]{
            MODEL_QWEN_MT_PLUS,
            MODEL_QWEN_MT_TURBO,
            MODEL_QWEN_TURBO,
            MODEL_QWEN_PLUS,
            MODEL_QWEN_MAX
        };
    }

    /**
     * 检查是否为翻译专用模型
     */
    public static boolean isTranslationModel(String model) {
        return model != null && model.startsWith("qwen-mt");
    }
}
