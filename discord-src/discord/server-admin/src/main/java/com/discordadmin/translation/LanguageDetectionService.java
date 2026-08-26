package com.discordadmin.translation;

import com.discordadmin.entity.AISetting;
import com.discordadmin.repository.AISettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 语言检测服务
 */
@Service
public class LanguageDetectionService {

    private static final Logger log = LoggerFactory.getLogger(LanguageDetectionService.class);

    @Autowired
    private AISettingRepository aiSettingRepository;

    @Autowired
    private QianwenTranslationService qianwenService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 语言代码映射（AI 翻译模型支持的所有语种，显示名称为中文）
    public static final Map<String, String> LANGUAGE_NAMES = new LinkedHashMap<>();
    static {
        LANGUAGE_NAMES.put("zh", "中文");
        LANGUAGE_NAMES.put("en", "英语");
        LANGUAGE_NAMES.put("ja", "日语");
        LANGUAGE_NAMES.put("ko", "韩语");
        LANGUAGE_NAMES.put("fr", "法语");
        LANGUAGE_NAMES.put("de", "德语");
        LANGUAGE_NAMES.put("es", "西班牙语");
        LANGUAGE_NAMES.put("ru", "俄语");
        LANGUAGE_NAMES.put("pt", "葡萄牙语");
        LANGUAGE_NAMES.put("it", "意大利语");
        LANGUAGE_NAMES.put("ar", "阿拉伯语");
        LANGUAGE_NAMES.put("th", "泰语");
        LANGUAGE_NAMES.put("vi", "越南语");
        LANGUAGE_NAMES.put("id", "印尼语");
        LANGUAGE_NAMES.put("hi", "印地语");
        LANGUAGE_NAMES.put("tr", "土耳其语");
        LANGUAGE_NAMES.put("nl", "荷兰语");
        LANGUAGE_NAMES.put("pl", "波兰语");
        LANGUAGE_NAMES.put("sv", "瑞典语");
    }

    /**
     * 返回当前 AI 翻译模型支持的所有语种列表
     * 供前端语种选择下拉框使用
     */
    public List<Map<String, String>> getSupportedLanguages() {
        List<Map<String, String>> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : LANGUAGE_NAMES.entrySet()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("code", entry.getKey());
            item.put("name", entry.getValue());
            list.add(item);
        }
        return list;
    }

    /**
     * 检测文本语言
     * @param text 要检测的文本
     * @param merchantId 商户ID
     * @return 语言代码和名称
     */
    public LanguageResult detect(String text, Long merchantId) {
        if (text == null || text.isBlank()) {
            return new LanguageResult("unknown", "未知", 0.0);
        }

        // 先用快速启发式检测
        LanguageResult fastResult = fastDetect(text);
        if (fastResult.confidence > 0.8) {
            return fastResult;
        }

        // 如果配置了AI翻译，尝试用AI检测
        AISetting setting = null;
        if (merchantId != null) {
            setting = aiSettingRepository.findByMerchantIdAndFeature(merchantId, "translate").orElse(null);
        }
        if (setting != null && Boolean.TRUE.equals(setting.getEnabled()) 
                && setting.getProvider() != null 
                && !"free".equals(setting.getProvider())
                && setting.getApiKey() != null && !setting.getApiKey().isBlank()) {
            try {
                LanguageResult aiResult = detectWithAI(text, setting);
                if (aiResult != null && aiResult.confidence > 0.5) {
                    return aiResult;
                }
            } catch (Exception e) {
                log.debug("AI语言检测失败: {}", e.getMessage());
            }
        }

        return fastResult;
    }

    /**
     * 快速启发式语言检测
     */
    private LanguageResult fastDetect(String text) {
        // 中文字符检测
        long chineseCount = countMatches(text, "[\\u4e00-\\u9fa5]");
        long hiraganaCount = countMatches(text, "[\\u3040-\\u309F]");  // 平假名
        long katakanaCount = countMatches(text, "[\\u30A0-\\u30FF]");  // 片假名
        long japaneseKanaCount = hiraganaCount + katakanaCount;  // 所有假名
        long koreanCount = countMatches(text, "[\\uAC00-\\uD7AF]");
        long arabicCount = countMatches(text, "[\\u0600-\\u06FF]");
        long thaiCount = countMatches(text, "[\\u0E00-\\u0E7F]");
        long cyrillicCount = countMatches(text, "[\\u0400-\\u04FF]");
        long totalChars = text.replaceAll("\\s", "").length();

        if (totalChars == 0) {
            return new LanguageResult("unknown", "未知", 0.0);
        }

        // 日文检测（优先检测：只要有假名就是日文，因为中文不会使用假名）
        // 支持仅有假名的日文（如"をしています"）和假名+汉字混合的日文
        if (japaneseKanaCount > 0) {
            double confidence = (double) (japaneseKanaCount + chineseCount) / totalChars;
            return new LanguageResult("ja", "日文", Math.min(confidence, 0.95));
        }

        // 中文检测（没有假名的情况下，有汉字就是中文）
        if (chineseCount > 0) {
            double confidence = (double) chineseCount / totalChars;
            return new LanguageResult("zh", "中文", confidence);
        }

        // 韩文检测
        if (koreanCount > 0) {
            double confidence = (double) koreanCount / totalChars;
            return new LanguageResult("ko", "韩文", confidence);
        }

        // 阿拉伯文检测
        if (arabicCount > 0) {
            double confidence = (double) arabicCount / totalChars;
            return new LanguageResult("ar", "阿拉伯文", confidence);
        }

        // 泰文检测
        if (thaiCount > 0) {
            double confidence = (double) thaiCount / totalChars;
            return new LanguageResult("th", "泰文", confidence);
        }

        // 俄文检测（西里尔字母）
        if (cyrillicCount > 0) {
            double confidence = (double) cyrillicCount / totalChars;
            return new LanguageResult("ru", "俄文", confidence);
        }

        // 拉丁文检测
        long latinCount = countMatches(text, "[a-zA-Z]");
        if (latinCount > totalChars * 0.5) {
            // 尝试检测是否为特定欧洲语言
            String lowerText = text.toLowerCase();
            if (lowerText.matches(".*\\b(le|la|les|une|des|est|dans|pour|avec|mais|donc)\\b.*")) {
                return new LanguageResult("fr", "法文", 0.7);
            }
            if (lowerText.matches(".*\\b(der|die|das|und|ist|mit|nicht|auf|fuer|wird)\\b.*")) {
                return new LanguageResult("de", "德文", 0.7);
            }
            if (lowerText.matches(".*\\b(el|la|los|las|es|porque|cuando|donde|pero|muy)\\b.*")) {
                return new LanguageResult("es", "西班牙文", 0.7);
            }
            if (lowerText.matches(".*\\b(il|la|gli|le|un|una|che|non|sono|piu|anche)\\b.*")) {
                return new LanguageResult("it", "意大利文", 0.7);
            }
            return new LanguageResult("en", "英文", (double) latinCount / totalChars);
        }

        return new LanguageResult("unknown", "未知", 0.3);
    }

    /**
     * 使用AI检测语言
     */
    private LanguageResult detectWithAI(String text, AISetting setting) {
        if (text.length() > 100) {
            text = text.substring(0, 100);
        }

        try {
            String model = setting.getModel() != null ? setting.getModel() : "qwen-turbo";
            String endpoint = setting.getApiEndpoint() != null ? setting.getApiEndpoint() 
                    : "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
            
            String prompt = "请检测以下文本的语言，只返回语言代码（如zh, en, ja, ko, fr, de, es, ru, pt, it等）：\n" + text;
            
            String requestBody;
            if (endpoint.contains("compatible-mode")) {
                // 兼容模式请求格式
                requestBody = String.format(
                    "{\"model\":\"%s\",\"messages\":[{\"role\":\"system\",\"content\":\"你是语言检测助手\"},{\"role\":\"user\",\"content\":\"%s\"}],\"temperature\":0.1}",
                    model,
                    prompt.replace("\"", "\\\"").replace("\n", "\\n"));
            } else {
                // 原生API请求格式
                requestBody = String.format(
                    "{\"model\":\"%s\",\"input\":{\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}]}}",
                    model,
                    prompt.replace("\"", "\\\"").replace("\n", "\\n"));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + setting.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            
            // 尝试兼容模式响应格式
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode messageNode = choices.get(0).path("message");
                String content = messageNode.path("content").asText("").trim();
                String langCode = extractLangCode(content);
                if (langCode != null) {
                    String langName = LANGUAGE_NAMES.getOrDefault(langCode, langCode);
                    return new LanguageResult(langCode, langName, 0.9);
                }
            }

            // 尝试原生API响应格式
            JsonNode output = root.path("output");
            if (!output.isMissingNode()) {
                choices = output.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    String content = choices.get(0).path("message").path("content").asText("").trim();
                    String langCode = extractLangCode(content);
                    if (langCode != null) {
                        String langName = LANGUAGE_NAMES.getOrDefault(langCode, langCode);
                        return new LanguageResult(langCode, langName, 0.9);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("AI语言检测请求失败: {}", e.getMessage());
        }

        return null;
    }

    private String extractLangCode(String content) {
        // 直接匹配已知语言代码
        for (String code : LANGUAGE_NAMES.keySet()) {
            if (content.toLowerCase().contains(code.toLowerCase())) {
                return code;
            }
        }
        
        // 尝试从JSON中提取
        try {
            JsonNode node = objectMapper.readTree(content);
            return node.path("language").asText(null);
        } catch (Exception e) {
            // 忽略
        }

        // 尝试从文本中提取
        Pattern pattern = Pattern.compile("[a-zA-Z]{2,3}");
        java.util.regex.Matcher m = pattern.matcher(content);
        if (m.find()) {
            return m.group().toLowerCase();
        }

        return null;
    }

    private long countMatches(String text, String regex) {
        Pattern pattern = Pattern.compile(regex);
        java.util.regex.Matcher matcher = pattern.matcher(text);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 语言检测结果
     */
    public static class LanguageResult {
        private final String code;
        private final String name;
        private final double confidence;

        public LanguageResult(String code, String name, double confidence) {
            this.code = code;
            this.name = name;
            this.confidence = confidence;
        }

        public String getCode() { return code; }
        public String getName() { return name; }
        public double getConfidence() { return confidence; }
        public boolean isDetected() { return !"unknown".equals(code); }
    }
}
