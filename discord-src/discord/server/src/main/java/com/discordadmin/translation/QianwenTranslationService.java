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
     * 调用策略：
     *  1) 优先按用户配置的 model + endpoint 调用；
     *  2) 若结果为空 / 返回非 200 / 抛异常：自动回退到 compatible-mode chat/completions + 通用 qwen-plus 模型
     *     （DashScope 账号在开通 qwen-mt-plus 权限时往往也开通 qwen-plus；且 qwen-plus 的聊天接口 prompt 翻译稳定）
     * @param text 要翻译的文本
     * @param targetLanguage 目标语言
     * @param setting AI配置
     * @return 翻译后的文本
     */
    public Optional<String> translate(String text, String targetLanguage, AISetting setting) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        final String sourceLang = detectLanguageHint(text);
        final String targetName = getTargetLanguageName(targetLanguage);

        // 1) 按用户配置尝试
        String apiKey = setting.getApiKey();
        String model = setting.getModel() != null ? setting.getModel() : MODEL_QWEN_PLUS;
        String endpoint = setting.getApiEndpoint() != null ? setting.getApiEndpoint() : COMPATIBLE_ENDPOINT;

        Optional<String> r1 = tryTranslateOnce(apiKey, model, endpoint, setting, text, sourceLang, targetName, "config[" + model + "]");
        if (r1.isPresent()) return r1;

        // 2) 回退：compatible-mode + qwen-plus 通用模型（翻译 prompt 稳定）
        String fallbackModel = MODEL_QWEN_PLUS;
        if (fallbackModel.equals(model) && COMPATIBLE_ENDPOINT.equals(endpoint)) {
            // 已经是 qwen-plus + compatible，再用 qwen-turbo 试一次
            fallbackModel = MODEL_QWEN_TURBO;
        }
        return tryTranslateOnce(apiKey, fallbackModel, COMPATIBLE_ENDPOINT, setting, text, sourceLang, targetName, "fallback[" + fallbackModel + "]");
    }

    private Optional<String> tryTranslateOnce(String apiKey, String model, String endpoint, AISetting setting,
                                              String text, String sourceLang, String targetName, String tag) {
        try {
            String requestBody;
            if (isQwenMtModel(model)) {
                // Qwen-MT 翻译专用模型协议（兼容模式）：
                //  - messages 仅包含一条 user 消息（禁止 system 消息 / 多轮）
                //  - 通过顶层 translation_options 显式指定 source_lang / target_lang，
                //    避免把翻译 prompt 当普通文本一起送入，也避免 system 消息被 MT 模型拒绝
                // 参考：https://help.aliyun.com/zh/model-studio/
                requestBody = buildQwenMtRequestBody(model, text, sourceLang, targetName, setting);
            } else if (endpoint.contains("compatible-mode")) {
                String prompt = String.format(
                    "将以下%s文本翻译成%s，直接输出翻译结果，不要任何前缀、解释、引号或重复原文：\n\n%s",
                    sourceLang, targetName, text);
                requestBody = buildCompatibleRequestBody(model, prompt, setting);
            } else {
                String prompt = String.format(
                    "将以下%s文本翻译成%s，直接输出翻译结果，不要任何前缀、解释、引号或重复原文：\n\n%s",
                    sourceLang, targetName, text);
                requestBody = buildNativeRequestBody(model, prompt, setting);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            long startMs = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - startMs;

            if (response.statusCode() != 200) {
                log.warn("[{}] 千问翻译非200: status={} cost={}ms body(200)={}",
                        tag, response.statusCode(), cost,
                        response.body() == null ? "" : response.body().substring(0, Math.min(200, response.body().length())));
                return Optional.empty();
            }

            Optional<String> translated = parseResponse(response.body());
            if (!translated.isPresent() || translated.get().isBlank()) {
                log.warn("[{}] 千问翻译返回空: cost={}ms body={}", tag, cost,
                        response.body() == null ? "" : response.body().substring(0, Math.min(300, response.body().length())));
                return Optional.empty();
            }
            // 剔除模型偶尔返回的 "翻译结果：" 前缀和首尾换行
            String cleaned = translated.get()
                    .replaceAll("(?i)^\\s*(翻译结果|译文|translation|result)\\s*[:：\\-]?\\s*", "")
                    .trim();
            log.info("[{}] 千问翻译成功: textLen={} target={} cost={}ms", tag, text.length(), targetName, cost);
            return Optional.of(cleaned);
        } catch (Exception e) {
            log.warn("[{}] 千问翻译异常: {}", tag, e.toString());
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

    /**
     * 构建 Qwen-MT 翻译专用模型的请求体（兼容模式）
     * 重要约束（阿里云文档）：
     *   - messages 数组必须只包含 1 条 user 角色消息，禁止 system 角色和多轮
     *   - 源/目标语种通过顶层 translation_options 指定，
     *     translation_options.source_lang 若为 "auto" 则自动检测
     */
    private String buildQwenMtRequestBody(String model, String text, String sourceLangHint, String targetName, AISetting setting) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);

            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", text);
            body.put("messages", new Object[]{userMessage});

            // Qwen-MT 翻译专用参数（兼容模式下位于顶层，对应 OpenAI SDK 的 extra_body）
            Map<String, String> translationOpts = new HashMap<>();
            translationOpts.put("source_lang", convertToMtLanguage(sourceLangHint));
            translationOpts.put("target_lang", convertToMtLanguage(targetName));
            body.put("translation_options", translationOpts);

            if (setting.getTemperature() != null) {
                body.put("temperature", setting.getTemperature());
            }
            if (setting.getMaxTokens() != null) {
                body.put("max_tokens", setting.getMaxTokens());
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("构建Qwen-MT请求体失败", e);
            return String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"translation_options\":{\"source_lang\":\"%s\",\"target_lang\":\"%s\"}}",
                model,
                text.replace("\"", "\\\"").replace("\n", "\\n"),
                convertToMtLanguage(sourceLangHint),
                convertToMtLanguage(targetName));
        }
    }

    /**
     * 判断是否为 Qwen-MT 系列翻译模型
     */
    private static boolean isQwenMtModel(String model) {
        return model != null && model.startsWith("qwen-mt");
    }

    /**
     * 将语言显示名/标签转换为 Qwen-MT 识别的语种代码（常用映射，其余透传）
     * Qwen-MT 同时支持显示名（如 "Chinese"/"English"）和 ISO 两字母代码
     */
    private String convertToMtLanguage(String lang) {
        if (lang == null) return "auto";
        switch (lang.toLowerCase().replace("-", "")) {
            case "zh":
            case "zhcn":
            case "简体中文":
            case "中文":
            case "chinese":
            case "simplified chinese":
                return "Chinese";
            case "en":
            case "enus":
            case "eng":
            case "英文":
            case "英语":
            case "english":
                return "English";
            case "ja":
            case "jpn":
            case "日文":
            case "日语":
            case "japanese":
                return "Japanese";
            case "ko":
            case "kor":
            case "韩文":
            case "韩语":
            case "korean":
                return "Korean";
            case "fr":
            case "fra":
            case "法文":
            case "法语":
            case "french":
                return "French";
            case "de":
            case "deu":
            case "德文":
            case "德语":
            case "german":
                return "German";
            case "es":
            case "spa":
            case "西文":
            case "西班牙语":
            case "spanish":
                return "Spanish";
            case "ru":
            case "rus":
            case "俄文":
            case "俄语":
            case "russian":
                return "Russian";
            default:
                return lang;
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
