package com.discordadmin.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FreeGoogleTranslationService implements TranslationService {

    private static final Logger log = LoggerFactory.getLogger(FreeGoogleTranslationService.class);

    private static final String GOOGLE_ENDPOINT = "https://translate.google.com/translate_a/single"
            + "?client=gtx&sl=auto&dt=t&tl=%s&q=%s";
    private static final String MYMEMORY_ENDPOINT = "https://api.mymemory.translated.net/get?q=%s&langpair=%s";
    private static final String MYMEMORY_FALLBACK = "https://mymemory.translated.net/api/get?q=%s&langpair=%s";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 300;

    @Override
    public Optional<String> translate(String text, String targetLanguage) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        log.info("[translate] 开始翻译 text='{}', targetLang={}", text, targetLanguage);
        // 1) Google 翻译（带重试）
        Optional<String> result = translateWithRetry(text, targetLanguage);
        if (result.isPresent()) {
            return result;
        }

        // 2) MyMemory 主接口
        result = translateWithMyMemory(text, targetLanguage, MYMEMORY_ENDPOINT);
        if (result.isPresent()) {
            return result;
        }

        // 3) MyMemory 备用接口
        result = translateWithMyMemory(text, targetLanguage, MYMEMORY_FALLBACK);
        if (result.isPresent()) {
            return result;
        }

        log.warn("所有翻译服务均失败，原文返回 [textLen={}]", text.length());
        return Optional.empty();
    }

    private Optional<String> translateWithRetry(String text, String targetLanguage) {
        Exception lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                Optional<String> result = translateWithGoogle(text, targetLanguage);
                if (result.isPresent()) {
                    if (attempt > 0) {
                        log.info("Google 翻译在第{}次重试成功", attempt + 1);
                    }
                    return result;
                }
            } catch (Exception e) {
                lastError = e;
                log.info("[Google] 第{}次尝试异常: {}", attempt + 1, e.getMessage());
            }
        }
        if (lastError != null) {
            log.warn("Google 翻译全部重试失败: {}", lastError.getMessage());
        }
        return Optional.empty();
    }

    private Optional<String> translateWithGoogle(String text, String targetLanguage) throws Exception {
        String url = String.format(GOOGLE_ENDPOINT, targetLanguage,
                URLEncoder.encode(text, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.debug("Google 翻译返回非200: {}", response.statusCode());
            return Optional.empty();
        }
        String body = response.body();
        // Google 返回格式: [[["translated","",null,null,null]],...]
        // 解析失败时可能是 [["translated"]] 等变体
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode segments = root.get(0);
            if (segments == null || !segments.isArray()) {
                return Optional.empty();
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode segment : segments) {
                if (segment.isArray() && segment.size() > 0) {
                    JsonNode translatedPart = segment.get(0);
                    if (translatedPart != null && !translatedPart.isNull()) {
                        String part = translatedPart.asText();
                        if (part != null && !part.isBlank()) {
                            sb.append(part);
                        }
                    }
                }
            }
            String translated = sb.toString();
            return translated.isBlank() ? Optional.empty() : Optional.of(translated);
        } catch (Exception e) {
            // 兜底：正则提取
            Matcher m = Pattern.compile("\\\"([^\\\"]+)\\\"").matcher(body);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            while (m.find() && count < 10) {
                String g = m.group(1);
                if (!g.contains("null") && !g.contains(",")) {
                    sb.append(g).append(" ");
                }
                count++;
            }
            String translated = sb.toString().trim();
            return translated.isBlank() ? Optional.empty() : Optional.of(translated);
        }
    }

    private Optional<String> translateWithMyMemory(String text, String targetLanguage, String endpoint) {
        log.info("[MyMemory] 开始翻译 text='{}', targetLang={}", text, targetLanguage);
        try {
            // 用 auto 让 MyMemory 自动检测源语言，目标语言按用户传入的来
            String target = targetLanguage != null ? targetLanguage : "en";
            String langPair = "auto|" + target;
            String url = String.format(endpoint,
                    URLEncoder.encode(text, StandardCharsets.UTF_8),
                    URLEncoder.encode(langPair, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[MyMemory] 返回非200: {} body={}", response.statusCode(), response.body().substring(0, Math.min(200, response.body().length())));
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode responseData = root.path("responseData");
            if (responseData.isMissingNode()) {
                log.warn("[MyMemory] responseData 缺失 body={}", response.body().substring(0, Math.min(200, response.body().length())));
                return Optional.empty();
            }
            String translated = responseData.path("translatedText").asText(null);
            if (translated == null || translated.isBlank()) {
                return Optional.empty();
            }
            log.info("MyMemory 翻译成功 [textLen={}]", text.length());
            return Optional.of(translated);
        } catch (Exception e) {
            log.debug("MyMemory 翻译失败: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
