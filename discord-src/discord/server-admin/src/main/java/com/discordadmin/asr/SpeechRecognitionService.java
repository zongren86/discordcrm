package com.discordadmin.asr;

import com.discordadmin.entity.AISetting;
import com.discordadmin.repository.AISettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * 语音识别(ASR)服务：调用阿里云百炼(DashScope)。
 *
 * 调用策略（优先避免"DashScope去下载Discord CDN音频"这条高失败率链路）：
 *   1) 有本地音频字节 → 优先走 DashScope multimodal-generation 同步直传 (base64 data URI)，
 *      模型默认 qwen-audio-3.0-asr-flash（官方推荐，无需 OSS，同步返回）。
 *   2) 回退 → OpenAI 兼容 chat/completions 同步直传 (input_audio data URI)。
 *   3) 无音频字节且 audioPublicUrl 是稳定公网 URL（非 Discord CDN） → 走公网 URL 版 multimodal/chat，
 *      最后兜底 Paraformer/SenseVoice 异步 transcription (file_urls)。
 *
 * 优先复用 AI 配置中「翻译」功能配置的 qwen provider 的 apiKey & apiEndpoint，
 * 若用户专门配了 feature=asr 的 AISetting，则优先用 feature=asr。
 */
@Service
public class SpeechRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(SpeechRecognitionService.class);

    public static final String DASHSCOPE_ASYNC_SUBMIT = "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription";
    public static final String DASHSCOPE_ASYNC_POLL   = "https://dashscope.aliyuncs.com/api/v1/tasks";
    @Deprecated
    public static final String DASHSCOPE_COMPAT_WHISPER = "https://dashscope.aliyuncs.com/compatible-mode/v1/audio/transcriptions";
    public static final String DASHSCOPE_COMPAT_CHAT_ASR = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    public static final String DASHSCOPE_MULTIMODAL_GENERATION = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    public static final String MODEL_PARAFORMER_V2 = "paraformer-v2";
    public static final String MODEL_SENSEVOICE_V1 = "sensevoice-v1";
    public static final String MODEL_QWEN3_ASR_FLASH = "qwen3-asr-flash";
    public static final String MODEL_FUN_ASR = "fun-asr";
    /** 百炼 2025 年起推荐的最新 ASR 模型（支持同步直传）*/
    public static final String MODEL_QWEN_AUDIO_3_0_ASR_FLASH = "qwen-audio-3.0-asr-flash";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AISettingRepository aiSettingRepository;

    public SpeechRecognitionService(AISettingRepository aiSettingRepository) {
        this.aiSettingRepository = aiSettingRepository;
    }

    /** 把 AISetting.model 规范化为百炼 ASR 可用的白名单模型；
     *  如果配置的是翻译模型（qwen-mt-plus / qwen-plus 等）或聊天模型，统一回退到官方最新推荐的 qwen-audio-3.0-asr-flash。
     *  保留 paraformer-v2/sensevoice-v1/fun-asr 完整原名（仅用于用户显式要求、且 audioPublicUrl 为稳定公网 URL 的场景）。*/
    static String normalizeAsrModel(String model) {
        if (model == null || model.isBlank()) return MODEL_QWEN_AUDIO_3_0_ASR_FLASH;
        String m = model.trim();
        // 兼容带前缀的写法（如 dashscope://qwen3-asr-flash 或 qwen/qwen3-asr-flash）
        int slash = m.lastIndexOf('/');
        if (slash >= 0 && slash < m.length() - 1) {
            m = m.substring(slash + 1);
        }
        // —— 官方最新推荐（支持同步直传 base64，优先使用）——
        if (MODEL_QWEN_AUDIO_3_0_ASR_FLASH.equalsIgnoreCase(m)) {
            return MODEL_QWEN_AUDIO_3_0_ASR_FLASH;
        }
        // —— 历史/别名 → 统一升级到官方最新推荐名（解决旧名 404 / model not found 问题）
        if (MODEL_QWEN3_ASR_FLASH.equalsIgnoreCase(m)
                || m.equalsIgnoreCase("qwen3-asr")
                || m.equalsIgnoreCase("qwen-audio-asr-flash")
                || m.equalsIgnoreCase("qwen-asr-flash")) {
            return MODEL_QWEN_AUDIO_3_0_ASR_FLASH;
        }
        // —— 旧模型完整原名保留（仅用于用户显式要求异步 + 稳定公网 URL 场景）
        if (MODEL_PARAFORMER_V2.equalsIgnoreCase(m)
                || MODEL_SENSEVOICE_V1.equalsIgnoreCase(m)
                || MODEL_FUN_ASR.equalsIgnoreCase(m)
                || m.equalsIgnoreCase("paraformer")
                || m.equalsIgnoreCase("sensevoice")) {
            // paraformer/sensevoice 升不到最新直传就保留原名，后续策略链会按是否有 audioBytes 判是否跳过
            return m.toLowerCase();
        }
        // 非 ASR 模型（翻译/聊天模型，例如 qwen-mt-plus/qwen-plus/qwen-turbo/qwen-max 等）→ 回退默认（最新直传模型）
        return MODEL_QWEN_AUDIO_3_0_ASR_FLASH;
    }

    /** 转写结果 */
    public static class AsrResult {
        private final String text;
        private final String language;
        private final String taskId;
        public AsrResult(String text, String language, String taskId) {
            this.text = text; this.language = language; this.taskId = taskId;
        }
        public String text() { return text; }
        public String language() { return language; }
        public String taskId() { return taskId; }
    }

    /**
     * 对音频字节进行语音识别。
     *
     * @param merchantId        商户ID（用于查找 AISetting）
     * @param audioBytes        音频二进制（ogg/webm/mp3/wav 均可）
     * @param audioMimeType     MIME 类型，用于推断格式
     * @param audioDurationSecs 时长秒数（仅打日志）
     * @param audioPublicUrl    公网可访问的音频 URL（paraformer/sensevoice 异步模式需要）；通常传 Discord CDN audioUrl
     * @return 识别结果
     */
    public Optional<AsrResult> transcribe(Long merchantId, byte[] audioBytes,
                                          String audioMimeType, Integer audioDurationSecs,
                                          String audioPublicUrl) {
        if ((audioBytes == null || audioBytes.length == 0) && (audioPublicUrl == null || audioPublicUrl.isBlank())) {
            return Optional.empty();
        }
        AISetting setting = resolveAsrSetting(merchantId);
        if (setting == null || setting.getApiKey() == null || setting.getApiKey().isBlank()) {
            log.error("ASR 未找到可用的 AISetting (百炼 key 未配置或为空)。 merchantId={}", merchantId);
            throw new RuntimeException("未配置百炼 API Key，请在 AI 配置中配置 翻译或语音识别 的百炼 key");
        }
        String keyPreview = setting.getApiKey().length() <= 8
                ? setting.getApiKey()
                : (setting.getApiKey().substring(0, 4) + "..." + setting.getApiKey().substring(Math.max(0, setting.getApiKey().length() - 4)));
        log.info("ASR 配置命中: merchantId={} feature={} provider={} model(原配置)={} keyPreview={}",
                merchantId, setting.getFeature(), setting.getProvider(), setting.getModel(), keyPreview);

        String model = setting.getModel();
        model = normalizeAsrModel(model);
        final String finalModel = model;
        if (!finalModel.equalsIgnoreCase(setting.getModel() == null ? "" : setting.getModel())) {
            log.info("ASR 模型规范化: 原配置={} 实际使用={} (原因: 非语音识别白名单模型，回退默认)",
                    setting.getModel(), finalModel);
        }
        final String key = setting.getApiKey();
        final String fileFormat = inferFormat(audioMimeType, audioBytes);
        final String filename  = "audio." + fileFormat;
        log.info("ASR 格式推断链路: merchantId={} audioMimeType(入参)={} bytesLen={} inferFormat结果={} safeFormat={} (若为空则被 safeFormat 兜底为 ogg)",
                merchantId, audioMimeType,
                audioBytes == null ? 0 : audioBytes.length,
                fileFormat, safeFormat(fileFormat));

        final boolean fHasBytes = audioBytes != null && audioBytes.length > 0;
        final boolean fHasUrl = audioPublicUrl != null && !audioPublicUrl.isBlank();
        final boolean fUrlIsPublicStable = fHasUrl && isPublicStableUrl(audioPublicUrl);
        final boolean fNeedAsync = !fHasBytes && fUrlIsPublicStable && (
                finalModel.equalsIgnoreCase(MODEL_PARAFORMER_V2)
                        || finalModel.equalsIgnoreCase(MODEL_SENSEVOICE_V1)
                        || finalModel.equalsIgnoreCase(MODEL_FUN_ASR));
        final byte[] fAudioBytes = audioBytes;
        final String fAudioMimeType = audioMimeType;
        final Integer fDuration = audioDurationSecs;
        final String fAudioPublicUrl = audioPublicUrl;
        // 统一用最新推荐名做直传：避免旧模型名 404；显式配置 paraformer-v2/sensevoice-v1 时仅在"异步公网URL"场景保留原名
        final String fDirectModel = MODEL_QWEN_AUDIO_3_0_ASR_FLASH;

        try {
            List<RunnableAttempt> attempts = new ArrayList<>();
            // ==========================================================
            // 有本地音频字节 → 优先走"直传"链（同步，DashScope无需拉公网）
            // ==========================================================
            if (fHasBytes) {
                // 策略1：DashScope 官方同步 multimodal-generation（最稳）
                attempts.add(() -> transcribeDashscopeMultimodal(key, fDirectModel, fAudioBytes, null, fileFormat));
                // 策略2：OpenAI 兼容 chat/completions 同步直传 base64（作为 multimodal 的回退）
                attempts.add(() -> transcribeChatAsrCompatible(key, fDirectModel, fAudioBytes, null, fileFormat));
                // 策略3（罕见兜底）：如果用户显式指定旧模型名，仍走 DashScope multimodal（传旧 model），避免因模型差异
                if (!fDirectModel.equalsIgnoreCase(finalModel)
                        && !MODEL_PARAFORMER_V2.equalsIgnoreCase(finalModel)
                        && !MODEL_SENSEVOICE_V1.equalsIgnoreCase(finalModel)
                        && !MODEL_FUN_ASR.equalsIgnoreCase(finalModel)) {
                    attempts.add(() -> transcribeDashscopeMultimodal(key, finalModel, fAudioBytes, null, fileFormat));
                }
            }
            // ==========================================================
            // 无音频字节 或 直传全失败 再尝试 公网 URL 链
            // 注意：Discord CDN URL 会被 DashScope 服务器拒绝 (403/过期/限流)，
            //       仅当 URL 是"稳定公网 URL"(非 Discord CDN) 时才走公网 multimodal/异步 file_urls。
            // ==========================================================
            if (fHasUrl) {
                if (fUrlIsPublicStable) {
                    // 稳定公网 URL：优先走同步 multimodal（audio=url）
                    attempts.add(() -> transcribeDashscopeMultimodal(key, fDirectModel, null, fAudioPublicUrl, fileFormat));
                    attempts.add(() -> transcribeChatAsrCompatible(key, fDirectModel, null, fAudioPublicUrl, fileFormat));
                    // 用户显式指定 paraformer-v2/sensevoice-v1/fun-asr → 走官方异步 transcription (file_urls)
                    if (MODEL_SENSEVOICE_V1.equalsIgnoreCase(finalModel)
                            || MODEL_PARAFORMER_V2.equalsIgnoreCase(finalModel)
                            || MODEL_FUN_ASR.equalsIgnoreCase(finalModel)) {
                        attempts.add(() -> transcribeAsyncWithFileUrls(key, finalModel, fAudioBytes, fileFormat, fDuration, fAudioPublicUrl));
                    }
                    // 最后兜底两套异步（即使前面没显式指定，尝试 sensevoice→paraformer）
                    attempts.add(() -> transcribeAsyncWithFileUrls(key, MODEL_SENSEVOICE_V1, fAudioBytes, fileFormat, fDuration, fAudioPublicUrl));
                    attempts.add(() -> transcribeAsyncWithFileUrls(key, MODEL_PARAFORMER_V2, fAudioBytes, fileFormat, fDuration, fAudioPublicUrl));
                } else {
                    // Discord CDN URL → 不直接用 file_urls（DashScope 服务器会 403/过期）。
                    // 但若前面 fHasBytes=true 已经尝试过直传链，应该早就成功了。
                    // 仍无音频字节（下载失败）→ 给一个明确错误提示。
                    if (!fHasBytes) {
                        attempts.add(() -> {
                            throw new RuntimeException("音频字节为空且 Discord CDN URL 不支持直接被 DashScope 拉取（会被 403 或 URL 过期）。" +
                                    "请修复后台音频下载链路（audioData）或手动将音频上传到稳定公网 OSS 后再识别。");
                        });
                    }
                }
            }

            if (attempts.isEmpty()) {
                throw new RuntimeException("既无音频字节也无可识别的公网 URL，无法进行语音转文字");
            }

            List<String> errors = new ArrayList<>();
            int i = 0;
            for (RunnableAttempt att : attempts) {
                i++;
                try {
                    Optional<AsrResult> r = att.run();
                    if (r.isPresent() && r.get().text() != null && !r.get().text().isBlank()) {
                        if (i > 1) {
                            log.info("ASR 使用第 {} 套策略成功(前序失败共 {} 条): merchantId={}", i, errors.size(), merchantId);
                        }
                        return r;
                    }
                } catch (Throwable t) {
                    String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    errors.add("[" + i + "]" + msg);
                    log.warn("ASR 策略#{} 失败: merchantId={} err={}", i, merchantId, truncate(msg, 300));
                }
            }
            throw new RuntimeException("ASR 所有策略均失败：" + String.join(" | ", errors));
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("ASR 转写异常: merchantId={} model={} size={}kb err={}", merchantId, model,
                    audioBytes == null ? 0 : audioBytes.length / 1024, e.getMessage());
            throw new RuntimeException("ASR调用异常: " + e.getMessage(), e);
        }
    }

    private static boolean isPublicStableUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String u = url.toLowerCase();
        // Discord / cdn 域名：DashScope 服务器访问时会被 403/Referer 拦截或 签名过期
        boolean discord = u.contains("cdn.discordapp.com") || u.contains("media.discordapp.net")
                || u.contains("discord.com") || u.contains("discord.gg");
        if (discord) return false;
        return u.startsWith("http://") || u.startsWith("https://");
    }

    @FunctionalInterface
    private interface RunnableAttempt {
        Optional<AsrResult> run() throws Exception;
    }

    /**
     * 兼容旧签名（无 audioPublicUrl 参数），保留给历史调用点。
     */
    public Optional<AsrResult> transcribe(Long merchantId, byte[] audioBytes,
                                          String audioMimeType, Integer audioDurationSecs) {
        return transcribe(merchantId, audioBytes, audioMimeType, audioDurationSecs, null);
    }

    // ============================ 策略一：DashScope multimodal-generation 同步直传（官方推荐） ============================
    /**
     * 调用百炼 DashScope 官方同步接口 /api/v1/services/aigc/multimodal-generation/generation。
     * 官方文档： https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference
     * 官方 cURL 结构（Qwen-Audio-3.0-ASR-Flash 同步）：
     *   {
     *     "model": "qwen-audio-3.0-asr-flash",
     *     "input": {
     *       "messages": [{
     *         "role": "user",
     *         "content": [{ "type": "input_audio", "input_audio": {"data": "<dataURI或公网URL>"} }]
     *       }]
     *     },
     *     "parameters": { "format": "ogg", "sample_rate": "16000" }
     *   }
     * 响应结构（注意：同步multimodal接口有两层output）：
     *   { "output": { "text": "<识别结果>", "output": { "sentence": {"text": "<识别结果>"} } }, "request_id": "..." }
     */
    private Optional<AsrResult> transcribeDashscopeMultimodal(String key, String model,
                                                              byte[] audioBytes, String audioPublicUrl,
                                                              String fileFormat) throws Exception {
        if ((audioBytes == null || audioBytes.length == 0)
                && (audioPublicUrl == null || audioPublicUrl.isBlank())) {
            throw new RuntimeException("DashScope multimodal ASR 需要 audioBytes 或 audioPublicUrl 至少一个");
        }
        String fmt = safeFormat(fileFormat);
        Object audioPayload;    // input_audio 字段的值，官方现在要求是对象 {data: xxx} 而非字符串
        if (audioBytes != null && audioBytes.length > 0) {
            String mime = mimeFromFormat(fmt);
            String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(audioBytes);
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("data", dataUri);
            audioPayload = wrap;
        } else {
            audioPayload = audioPublicUrl;
            // 纯 URL 场景：尽量从 URL 扩展名再补一次 format 推断，避免 fmt 推断不准
            if ("ogg".equals(fmt) && audioPublicUrl != null && !audioPublicUrl.isBlank()) {
                String ext = formatFromUrl(audioPublicUrl);
                if (ext != null && !ext.isBlank()) fmt = ext;
            }
        }
        // 按官方协议组装 JSON —— format 放 parameters.format（直接字段，不是 asr_options.format）
        Map<String, Object> userAudioPart = new LinkedHashMap<>();
        userAudioPart.put("type", "input_audio");
        userAudioPart.put("input_audio", audioPayload);

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", Collections.singletonList(userAudioPart));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", Collections.singletonList(userMsg));

        // parameters.format 放这里（官方要求的位置）
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("format", fmt);
        parameters.put("language", "auto");
        // 某些后端版本仍认 asr_options；为兼容性同时带上（不作为 format 的主要位置）
        Map<String, Object> asrOpts = new LinkedHashMap<>();
        asrOpts.put("enable_lid", true);
        asrOpts.put("enable_itn", true);
        parameters.put("asr_options", asrOpts);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        String bodyStr = objectMapper.writeValueAsString(body);
        // 把请求体中的 base64 截断后打日志，便于人工核对 format 字段是否真的发出且非空
        String bodyForLog = bodyStr
                .replaceAll("\"data\":\"data:[^\"]{60,}?\"",
                        "\"data\":\"data:<truncated " + (audioBytes == null ? 0 : audioBytes.length / 1024) + "KB>\"")
                .replaceAll("\"input_audio\":\"data:[^\"]{60,}?\"",
                        "\"input_audio\":\"data:<truncated " + (audioBytes == null ? 0 : audioBytes.length / 1024) + "KB>\"");
        log.info("ASR(DashScope multimodal)请求: endpoint={} model={} hasBytes={} bytesKB={} urlType={} fmt(实际发出)={}\n请求体(截断audio)={}",
                DASHSCOPE_MULTIMODAL_GENERATION, model,
                (audioBytes != null && audioBytes.length > 0),
                (audioBytes == null ? 0 : audioBytes.length / 1024),
                (audioPublicUrl == null ? "none" : isPublicStableUrl(audioPublicUrl) ? "public" : "discord-cdn"),
                fmt, bodyForLog);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_MULTIMODAL_GENERATION))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String msg = extractDashscopeErrorMessage(resp.body());
            log.warn("DashScope multimodal ASR失败: status={} body={}", resp.statusCode(), truncate(resp.body(), 1500));
            throw new RuntimeException("ASR(DashScopeMultimodal)失败: " + (msg == null ? "HTTP " + resp.statusCode() : msg));
        }
        log.info("ASR(DashScope multimodal)响应: status={} body={}", resp.statusCode(), truncate(resp.body(), 1500));
        JsonNode root = objectMapper.readTree(resp.body());
        String text = extractTextFromAny(root);
        String lang = extractLanguageFromAny(root);
        if (text == null || text.isBlank()) {
            throw new RuntimeException("ASR返回空文本（HTTP " + resp.statusCode() + "）");
        }
        return Optional.of(new AsrResult(text, lang, null));
    }

    // ============================ 策略二：OpenAI 兼容 chat/completions 同步直传（multimodal-generation 的回退） ============================
    /**
     * 调用百炼 compatible-mode/v1/chat/completions，按官方协议传 type=input_audio。
     * 官方协议（Qwen3-ASR-Flash OpenAI兼容）：
     *   body = {
     *     "model": "qwen-audio-3.0-asr-flash",
     *     "messages": [{
     *       "role": "user",
     *       "content": [{ "type": "input_audio", "input_audio": "<dataURI或公网URL（字符串）>" }]
     *     }],
     *     "asr_options": { "enable_lid": true, "enable_itn": true, "format": "ogg" }
     *   }
     * 注意：format 推断优先走 data URI mime + asr_options.format 双重保险；官方文档中 asr_options 列的是 language/enable_itn，
     *       但部分版本仍接受 format 字段，此处同时发出以兼容不同后端。
     */
    private Optional<AsrResult> transcribeChatAsrCompatible(String key, String model,
                                                            byte[] audioBytes, String audioPublicUrl,
                                                            String fileFormat) throws Exception {
        if ((audioBytes == null || audioBytes.length == 0)
                && (audioPublicUrl == null || audioPublicUrl.isBlank())) {
            throw new RuntimeException("Chat兼容ASR 需要 audioBytes 或 audioPublicUrl 至少一个");
        }
        String fmt = safeFormat(fileFormat);
        String inputAudio;
        if (audioBytes != null && audioBytes.length > 0) {
            String mime = mimeFromFormat(fmt);
            inputAudio = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(audioBytes);
        } else {
            inputAudio = audioPublicUrl;
            if ("ogg".equals(fmt) && audioPublicUrl != null && !audioPublicUrl.isBlank()) {
                String ext = formatFromUrl(audioPublicUrl);
                if (ext != null && !ext.isBlank()) fmt = ext;
            }
        }

        Map<String, Object> userAudioPart = new LinkedHashMap<>();
        userAudioPart.put("type", "input_audio");
        userAudioPart.put("input_audio", inputAudio);
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", Collections.singletonList(userAudioPart));

        Map<String, Object> asrOpts = new LinkedHashMap<>();
        asrOpts.put("enable_lid", true);
        asrOpts.put("enable_itn", true);
        asrOpts.put("format", fmt);
        asrOpts.put("language", "auto");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", Collections.singletonList(userMsg));
        body.put("asr_options", asrOpts);
        // 再兜底：部分后端实现要求 format / language 与 messages 同级
        body.put("format", fmt);
        body.put("language", "auto");

        String bodyStr = objectMapper.writeValueAsString(body);
        String bodyForLog = bodyStr.replaceAll("\"input_audio\":\"data:[^\"]{60,}?\"",
                "\"input_audio\":\"data:<truncated " + (audioBytes == null ? 0 : audioBytes.length / 1024) + "KB>\"");
        log.info("ASR(Chat兼容)请求: endpoint={} model={} hasBytes={} bytesKB={} fmt(实际发出)={}\n请求体(截断audio)={}",
                DASHSCOPE_COMPAT_CHAT_ASR, model,
                (audioBytes != null && audioBytes.length > 0),
                (audioBytes == null ? 0 : audioBytes.length / 1024),
                fmt, bodyForLog);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_COMPAT_CHAT_ASR))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String msg = extractDashscopeErrorMessage(resp.body());
            log.warn("Chat兼容ASR失败: status={} body={}", resp.statusCode(), truncate(resp.body(), 1500));
            throw new RuntimeException("ASR(Chat兼容)失败: " + (msg == null ? "HTTP " + resp.statusCode() : msg));
        }
        log.info("ASR(Chat兼容)响应: status={} body={}", resp.statusCode(), truncate(resp.body(), 1500));
        JsonNode root = objectMapper.readTree(resp.body());
        // Chat 兼容输出：choices[0].message.content 可能是字符串或数组
        String text = extractTextFromChatResponse(root);
        if (text == null) text = extractTextFromAny(root);
        String lang = extractLanguageFromAny(root);
        if (text == null || text.isBlank()) {
            throw new RuntimeException("ASR返回空文本（HTTP " + resp.statusCode() + "，chat响应无法提取文本）");
        }
        return Optional.of(new AsrResult(text, lang, null));
    }

    /** format 防御性归一化：任何 null/空/未知都回退到 ogg（Discord 手机端语音默认容器）*/
    private static String safeFormat(String fileFormat) {
        if (fileFormat == null) return "ogg";
        String f = fileFormat.trim().toLowerCase();
        if (f.isBlank()) return "ogg";
        switch (f) {
            case "mp3":
            case "wav":
            case "webm":
            case "m4a":
            case "mp4":
            case "flac":
            case "aac":
            case "opus":
            case "ogg":
                return f;
            default:
                return "ogg";
        }
    }

    /** 从 URL 路径部分按扩展名提取 format（忽略 query）*/
    private static String formatFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = URI.create(url);
            String path = u.getPath();
            if (path == null) return null;
            int slash = path.lastIndexOf('/');
            String name = slash < 0 ? path : path.substring(slash + 1);
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) return null;
            String ext = name.substring(dot + 1).toLowerCase();
            switch (ext) {
                case "mp3":
                case "wav":
                case "webm":
                case "m4a":
                case "mp4":
                case "flac":
                case "aac":
                case "opus":
                case "ogg":
                    return ext;
                default:
                    return null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private static String mimeFromFormat(String fileFormat) {
        switch (fileFormat == null ? "" : fileFormat.toLowerCase()) {
            case "mp3":  return "audio/mpeg";
            case "wav":  return "audio/wav";
            case "webm": return "audio/webm";
            case "m4a":  return "audio/mp4";
            case "mp4":  return "audio/mp4";
            case "flac": return "audio/flac";
            case "aac":  return "audio/aac";
            case "opus": return "audio/opus";
            case "ogg":
            default:     return "audio/ogg";
        }
    }

    private String extractTextFromChatResponse(JsonNode root) {
        if (root == null) return null;
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) return null;
        JsonNode first = choices.get(0);
        JsonNode contentNode = first.path("message").get("content");
        if (contentNode == null || contentNode.isMissingNode()) return null;
        if (contentNode.isTextual()) {
            String s = contentNode.asText(null);
            return s == null ? null : s.trim();
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode p : contentNode) {
                String t = p.path("text").asText(null);
                if (t != null && !t.isBlank()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(t.trim());
                }
            }
            return sb.length() > 0 ? sb.toString().trim() : null;
        }
        return null;
    }

    // ============================ 旧策略（保留/仍可用，但不再默认第一优先）：Qwen3-ASR-Flash OpenAI Whisper 兼容 ============================
    private Optional<AsrResult> transcribeQwen3WhisperCompatible(String key, String model,
                                                                  byte[] audioBytes, String audioMimeType,
                                                                  String filename, String fileFormat, Integer audioDurationSecs) throws Exception {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new RuntimeException("无法走直传ASR：无音频字节，请检查语音下载链路");
        }
        String boundary = "----DiscordAsrBoundary" + Long.toHexString(System.currentTimeMillis());

        // 组装 multipart/form-data（Whisper 兼容：model + response_format=verbose_json + file=<bytes>）
        List<byte[]> parts = new ArrayList<>();
        parts.add(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\nverbose_json\r\n").getBytes(StandardCharsets.UTF_8));

        String mime = guessMimeForMultipart(audioMimeType, fileFormat);
        parts.add(("--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n" +
                "Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(audioBytes);
        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        int totalSize = 0;
        for (byte[] p : parts) totalSize += p.length;
        byte[] body = new byte[totalSize];
        int cursor = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, body, cursor, p.length); cursor += p.length; }

        log.info("ASR(Qwen3兼容)请求: endpoint={} model={} fileSize={}KB fileFormat={} mime={}",
                DASHSCOPE_COMPAT_WHISPER, model, audioBytes.length / 1024, fileFormat, mime);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_COMPAT_WHISPER))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String msg = extractDashscopeErrorMessage(resp.body());
            log.warn("Qwen3 Whisper兼容ASR失败: status={} body={}", resp.statusCode(), truncate(resp.body(), 1200));
            throw new RuntimeException("ASR(Qwen3兼容)失败: " + (msg == null ? "HTTP " + resp.statusCode() : msg));
        }
        log.info("ASR(Qwen3兼容)响应: status={} body={}", resp.statusCode(), truncate(resp.body(), 1200));
        JsonNode root = objectMapper.readTree(resp.body());
        String text = extractTextFromAny(root);
        String lang = extractLanguageFromAny(root);
        if (text == null || text.isBlank()) {
            throw new RuntimeException("ASR返回空文本（HTTP " + resp.statusCode() + "，无法从响应体提取 text）");
        }
        return Optional.of(new AsrResult(text, lang, null));
    }

    // ============================ 策略二：Paraformer/SenseVoice 异步 transcription（必须公网 file_urls） ============================
    private Optional<AsrResult> transcribeAsyncWithFileUrls(String key, String model, byte[] audioBytes, String fileFormat,
                                                            Integer audioDurationSecs, String audioPublicUrl) throws Exception {
        if ((audioPublicUrl == null || audioPublicUrl.isBlank())) {
            throw new RuntimeException("Paraformer/SenseVoice 需要公网音频URL，但当前消息 audioUrl 为空。请改用 Qwen3-ASR-Flash 模型或在前端提供公网 URL。");
        }
        // 校验是不是 http(s)
        String url = audioPublicUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new RuntimeException("Paraformer/SenseVoice 仅支持 http(s) 公网 URL。");
        }
        String body;
        {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("model", model);
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("file_urls", Collections.singletonList(url));
            b.put("input", in);
            Map<String, Object> params = new LinkedHashMap<>();
            if (MODEL_SENSEVOICE_V1.equalsIgnoreCase(model)) {
                params.put("language_hints", Collections.singletonList("auto"));
            } else {
                params.put("language_hints", Arrays.asList("zh", "en", "ja"));
            }
            params.put("disfluency_removal_enabled", false);
            b.put("parameters", params);
            body = objectMapper.writeValueAsString(b);
        }
        log.info("ASR异步创建任务: endpoint={} model={} url={} reqBody={}",
                DASHSCOPE_ASYNC_SUBMIT, model, truncate(url, 200), truncate(body, 400));
        HttpRequest createReq = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_ASYNC_SUBMIT))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
        if (createResp.statusCode() < 200 || createResp.statusCode() >= 300) {
            String msg = extractDashscopeErrorMessage(createResp.body());
            log.warn("ASR 创建异步任务失败: status={} body={}", createResp.statusCode(), truncate(createResp.body(), 1200));
            throw new RuntimeException("ASR创建任务失败: " + (msg == null ? "HTTP " + createResp.statusCode() : msg));
        }
        log.info("ASR 创建异步任务响应: status={} body={}", createResp.statusCode(), truncate(createResp.body(), 1200));
        JsonNode createRoot = objectMapper.readTree(createResp.body());
        String taskId = createRoot.path("output").path("task_id").asText(null);
        if (taskId == null) {
            String t = extractTextFromOutput(createRoot);
            String l = extractLanguageFromOutput(createRoot);
            if (t != null && !t.isBlank()) return Optional.of(new AsrResult(t, l, null));
            throw new RuntimeException("ASR创建任务未返回task_id: " + truncate(createResp.body(), 200));
        }

        long deadline = System.currentTimeMillis() + 120_000L;
        int sleepMs = 1200;
        int steps = 0;
        String pollUrl = DASHSCOPE_ASYNC_POLL + "/" + taskId;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(sleepMs);
            HttpRequest pollReq = HttpRequest.newBuilder()
                    .uri(URI.create(pollUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> pollResp = httpClient.send(pollReq, HttpResponse.BodyHandlers.ofString());
            if (pollResp.statusCode() < 200 || pollResp.statusCode() >= 300) {
                log.warn("ASR 轮询异常: taskId={} status={} body={}", taskId, pollResp.statusCode(), truncate(pollResp.body(), 800));
                if (++steps > 3) {
                    throw new RuntimeException("ASR轮询失败: HTTP " + pollResp.statusCode());
                }
                continue;
            }
            JsonNode root = objectMapper.readTree(pollResp.body());
            String status = root.path("output").path("task_status").asText(null);
            if (status == null) status = root.path("task_status").asText("UNKNOWN");
            switch (status.toUpperCase()) {
                case "SUCCEEDED":
                case "DONE": {
                    log.info("ASR 异步任务完成: taskId={} body={}", taskId, truncate(pollResp.body(), 1800));
                    String t = extractTextFromAsync(root);
                    String l = extractLanguageFromAsync(root);
                    if (t != null && !t.isBlank()) return Optional.of(new AsrResult(t, l, taskId));
                    throw new RuntimeException("ASR返回空文本（taskId=" + taskId + "）");
                }
                case "FAILED":
                case "CANCELED":
                case "CANCELLED": {
                    String err = root.path("output").path("message").asText(null);
                    if (err == null) err = root.path("message").asText(null);
                    log.warn("ASR 任务失败: taskId={} status={} err={} body={}",
                            taskId, status, err, truncate(pollResp.body(), 1200));
                    throw new RuntimeException("ASR任务失败: " + (err == null ? status : err));
                }
                default:
                    if (steps % 4 == 0) {
                        log.info("ASR 异步任务轮询中: taskId={} status={} steps={}", taskId, status, steps);
                    }
                    sleepMs = Math.min(4_000, (++steps * 300) + 1000);
            }
        }
        throw new RuntimeException("ASR任务超时(120s)");
    }

    private String guessMimeForMultipart(String audioMimeType, String fileFormat) {
        // ogg(opus) 和 webm 容器在 Whisper 兼容 multipart 常因 Content-Type 不匹配被拒，
        // 统一传 application/octet-stream，让 DashScope 根据文件头/format自行识别更稳定
        if (audioMimeType != null && !audioMimeType.isBlank()) {
            String lower = audioMimeType.toLowerCase();
            if (lower.contains("ogg") || lower.contains("opus") || lower.contains("webm")) {
                return "application/octet-stream";
            }
            return audioMimeType;
        }
        switch (fileFormat) {
            case "wav":  return "audio/wav";
            case "mp3":  return "audio/mpeg";
            case "m4a":  return "audio/mp4";
            case "flac": return "audio/flac";
            case "aac":  return "audio/aac";
            default:     return "application/octet-stream";
        }
    }

    private String extractDashscopeErrorMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode n = objectMapper.readTree(body);
            String m = n.path("message").asText(null);
            if (m != null && !m.isBlank()) return m;
            m = n.path("output").path("message").asText(null);
            if (m != null && !m.isBlank()) return m;
            // OpenAI 兼容格式
            m = n.path("error").path("message").asText(null);
            return m;
        } catch (Exception ex) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

    private String extractTextFromAsync(JsonNode root) {
        if (root == null) return null;
        JsonNode output = root.has("output") ? root.get("output") : root;
        // 异步：results[].transcription_url 或 results[].transcription_text / sentences[].text
        StringBuilder sb = new StringBuilder();
        JsonNode results = output.path("results");
        if (results.isArray() && !results.isEmpty()) {
            for (JsonNode r : results) {
                // subtask 成功时才有 transcription_text
                String subStatus = r.path("subtask_status").asText("");
                boolean ok = subStatus.isBlank() || subStatus.equalsIgnoreCase("SUCCEEDED");
                if (ok) {
                    String txt = r.path("transcription_text").asText(null);
                    if (txt == null) txt = r.path("text").asText(null);
                    if (txt != null && !txt.isBlank()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(txt.trim());
                    }
                    JsonNode sents = r.path("sentences");
                    if (sents.isArray()) {
                        for (JsonNode s : sents) {
                            String st = s.path("text").asText(null);
                            if (st != null && !st.isBlank()) {
                                if (sb.length() > 0) sb.append(' ');
                                sb.append(st.trim());
                            }
                        }
                    }
                    // 有的实现是 transcription_url 指向 JSON 文件，里面 transcription -> sentences -> text
                    String tu = r.path("transcription_url").asText(null);
                    if (tu != null && !tu.isBlank()) {
                        String remote = fetchTranscriptionUrl(tu);
                        if (remote != null && !remote.isBlank()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(remote);
                        }
                    }
                }
            }
            if (sb.length() > 0) return sb.toString().trim();
        }
        // 退化情况
        return extractTextFromOutput(root);
    }

    private String fetchTranscriptionUrl(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
            JsonNode n = objectMapper.readTree(resp.body());
            // 常见结构 {"transcription":{"sentences":[{"text":"xxx"}]}}
            StringBuilder sb = new StringBuilder();
            JsonNode sents = n.path("transcription").path("sentences");
            if (sents.isArray()) {
                for (JsonNode s : sents) {
                    String t = s.path("text").asText(null);
                    if (t != null && !t.isBlank()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(t.trim());
                    }
                }
            }
            if (sb.length() == 0) {
                String t = n.path("text").asText(null);
                if (t != null && !t.isBlank()) return t.trim();
            }
            return sb.length() > 0 ? sb.toString().trim() : null;
        } catch (Exception e) {
            log.warn("拉取 transcription_url 失败: url={} err={}", truncate(url, 120), e.getMessage());
            return null;
        }
    }

    private String extractLanguageFromAsync(JsonNode root) {
        if (root == null) return null;
        JsonNode output = root.has("output") ? root.get("output") : root;
        JsonNode results = output.path("results");
        if (results.isArray() && !results.isEmpty()) {
            for (JsonNode r : results) {
                String l = r.path("language").asText(null);
                if (l != null && !l.isBlank()) return l.toLowerCase();
                String l2 = r.path("properties").path("language").asText(null);
                if (l2 != null && !l2.isBlank()) return l2.toLowerCase();
            }
        }
        return extractLanguageFromOutput(root);
    }

    /**
     * 查询 feature=asr 配置，若无则复用 feature=translate provider=qwen/openai 的 key
     */
    private AISetting resolveAsrSetting(Long merchantId) {
        if (merchantId == null) {
            List<AISetting> all = aiSettingRepository.findAll();
            AISetting asr = all.stream().filter(s -> "asr".equals(s.getFeature())
                    && s.getEnabled() != null && s.getEnabled()).findFirst().orElse(null);
            if (asr != null) return asr;
            return all.stream().filter(s -> "translate".equals(s.getFeature())
                    && s.getEnabled() != null && s.getEnabled()).findFirst().orElse(null);
        }
        List<AISetting> byMerchant = aiSettingRepository.findByMerchantIdOrderByFeatureAsc(merchantId);
        AISetting asr = byMerchant.stream().filter(s -> "asr".equals(s.getFeature())
                && s.getEnabled() != null && s.getEnabled()).findFirst().orElse(null);
        if (asr != null) return asr;
        Optional<AISetting> translateOpt = byMerchant.stream().filter(s -> "translate".equals(s.getFeature())
                && s.getEnabled() != null && s.getEnabled()).findFirst();
        if (translateOpt.isPresent()) return translateOpt.get();
        // fallback: 全局的
        return resolveAsrSetting(null);
    }

    /** 根据 MIME / 文件头推断 DashScope 需要的 format 参数 */
    private String inferFormat(String mimeType, byte[] bytes) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase();
        if (mime.contains("webm")) return "webm";
        if (mime.contains("ogg") || mime.contains("opus")) return "ogg";
        if (mime.contains("wav") || mime.contains("wave")) return "wav";
        if (mime.contains("mpeg") || mime.contains("mp3")) return "mp3";
        if (mime.contains("mp4") || mime.contains("m4a")) return "m4a";
        if (mime.contains("flac")) return "flac";
        if (mime.contains("aac")) return "aac";
        // 按文件头推断
        if (bytes != null && bytes.length >= 4) {
            int h = (bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
            if (h == 0x1A45DFA3) return "webm"; // EBML
            if ((h & 0xFFFFFF00) == 0x4F676753) return "ogg"; // "OggS"
            if (h == 0x52494646 /* RIFF */ && bytes.length >= 12) {
                int f = (bytes[8] & 0xFF) << 24 | (bytes[9] & 0xFF) << 16 | (bytes[10] & 0xFF) << 8 | (bytes[11] & 0xFF);
                if (f == 0x57415645) return "wav"; // WAVE
            }
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0) return "mp3";
        }
        return "ogg";
    }

    /** 从 output 中抽取文本：兼容 paraformer/sensevoice 多种嵌套；支持同步 multimodal 的「两层 output」结构 */
    private String extractTextFromOutput(JsonNode root) {
        if (root == null) return null;
        // 同步模式兼容接口
        JsonNode output = root.has("output") ? root.get("output") : root;

        // —— DashScope 同步 multimodal 两层 output：root.output.output.sentence.text / output.output.text ——
        if (output.hasNonNull("output") && output.get("output").isObject()) {
            JsonNode nested = output.get("output");
            String nestedText = extractTextFromOutput(nested);   // 递归进内层output
            if (nestedText != null && !nestedText.isBlank()) return nestedText;
        }

        JsonNode results = output.path("results");
        if (results.isArray() && !results.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode r : results) {
                String t = r.path("transcription_text").asText(null);
                if (t == null) t = r.path("text").asText(null);
                if (t != null && !t.isBlank()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(t.trim());
                }
                JsonNode sents = r.path("sentences");
                if (sents.isArray()) {
                    for (JsonNode s : sents) {
                        String st = s.path("text").asText(null);
                        if (st != null && !st.isBlank()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(st.trim());
                        }
                    }
                }
                // sentence.text（DashScope 部分模型用 sentence 而非 sentences）
                JsonNode singleSentence = r.path("sentence").path("text");
                if (!singleSentence.isMissingNode()) {
                    String st = singleSentence.asText(null);
                    if (st != null && !st.isBlank()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(st.trim());
                    }
                }
            }
            if (sb.length() > 0) return sb.toString().trim();
        }

        // DashScope 常见：output.sentence.text
        JsonNode sentText = output.path("sentence").path("text");
        if (!sentText.isMissingNode()) {
            String st = sentText.asText(null);
            if (st != null && !st.isBlank()) return st.trim();
        }
        // output.sentences[].text
        JsonNode sentsArr = output.path("sentences");
        if (sentsArr.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode s : sentsArr) {
                String st = s.path("text").asText(null);
                if (st != null && !st.isBlank()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(st.trim());
                }
            }
            if (sb.length() > 0) return sb.toString().trim();
        }
        // output.transcription 可能是对象
        JsonNode transObj = output.get("transcription");
        if (transObj != null && transObj.isObject()) {
            String t = transObj.path("text").asText(null);
            if (t != null && !t.isBlank()) return t.trim();
            JsonNode ss = transObj.path("sentences");
            if (ss.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode s : ss) {
                    String st = s.path("text").asText(null);
                    if (st != null && !st.isBlank()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(st.trim());
                    }
                }
                if (sb.length() > 0) return sb.toString().trim();
            }
        }
        // chunks[].text / chunks[].alternatives[].text
        JsonNode chunks = output.path("chunks");
        if (chunks.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode c : chunks) {
                String t = c.path("text").asText(null);
                if (t != null && !t.isBlank()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(t.trim());
                }
                JsonNode alts = c.path("alternatives");
                if (alts.isArray()) {
                    for (JsonNode a : alts) {
                        String at = a.path("text").asText(null);
                        if (at != null && !at.isBlank()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(at.trim());
                        }
                    }
                }
            }
            if (sb.length() > 0) return sb.toString().trim();
        }

        String tc1 = output.path("transcription_text").asText(null);
        if (tc1 != null && !tc1.isBlank()) return tc1.trim();
        String tc = output.path("transcription").asText(null);
        if (tc != null && !tc.isBlank()) return tc.trim();
        String t = output.path("text").asText(null);
        if (t != null && !t.isBlank()) return t.trim();
        return null;
    }

    private String extractLanguageFromOutput(JsonNode root) {
        if (root == null) return null;
        JsonNode output = root.has("output") ? root.get("output") : root;
        JsonNode results = output.path("results");
        if (results.isArray() && !results.isEmpty()) {
            String l = results.get(0).path("language").asText(null);
            if (l != null && !l.isBlank()) return l.toLowerCase();
        }
        // sentence.language
        String ls = output.path("sentence").path("language").asText(null);
        if (ls != null && !ls.isBlank()) return ls.toLowerCase();
        // transcription.language
        String lt = output.path("transcription").path("language").asText(null);
        if (lt != null && !lt.isBlank()) return lt.toLowerCase();
        String l = output.path("language").asText(null);
        if (l != null && !l.isBlank()) return l.toLowerCase();
        if (results.isArray() && !results.isEmpty()) {
            String ll = results.get(0).path("properties").path("language").asText(null);
            if (ll != null && !ll.isBlank()) return ll.toLowerCase();
        }
        return null;
    }

    /** 通用抽取：同时兼容 OpenAI Whisper 兼容响应 (root.text/segments/language) 与 DashScope (root.output.xxx) */
    private String extractTextFromAny(JsonNode root) {
        if (root == null) return null;
        // 1. 优先 Whisper 兼容：root.text
        String t = root.path("text").asText(null);
        if (t != null && !t.isBlank()) return t.trim();

        // 2. Whisper verbose_json: root.segments[].text
        JsonNode segs = root.path("segments");
        if (segs.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode s : segs) {
                String st = s.path("text").asText(null);
                if (st != null && !st.isBlank()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(st.trim());
                }
            }
            if (sb.length() > 0) return sb.toString().trim();
        }

        // 3. DashScope 标准路径（含 output.sentence.text / results[] / chunks[] 等）
        String ds = extractTextFromOutput(root);
        if (ds != null && !ds.isBlank()) return ds.trim();

        // 4. output.transcription.sentences 里的 sentence.text
        JsonNode trans = root.path("output").path("transcription");
        if (trans.isObject()) {
            JsonNode ss = trans.path("sentences");
            if (ss.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode s : ss) {
                    String st = s.path("sentence").path("text").asText(null);
                    if (st == null) st = s.path("text").asText(null);
                    if (st != null && !st.isBlank()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(st.trim());
                    }
                }
                if (sb.length() > 0) return sb.toString().trim();
            }
            String t2 = trans.path("text").asText(null);
            if (t2 != null && !t2.isBlank()) return t2.trim();
        }
        return null;
    }

    /** 通用抽取语言：兼容 Whisper 兼容响应 + DashScope */
    private String extractLanguageFromAny(JsonNode root) {
        if (root == null) return null;
        String l = root.path("language").asText(null);
        if (l != null && !l.isBlank()) return l.toLowerCase();
        String l2 = extractLanguageFromOutput(root);
        if (l2 != null && !l2.isBlank()) return l2.toLowerCase();
        // segments[0].language
        JsonNode segs = root.path("segments");
        if (segs.isArray() && !segs.isEmpty()) {
            String ls = segs.get(0).path("language").asText(null);
            if (ls != null && !ls.isBlank()) return ls.toLowerCase();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
