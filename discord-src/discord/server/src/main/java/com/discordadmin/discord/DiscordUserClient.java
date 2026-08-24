package com.discordadmin.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DiscordUserClient {

    private static final Logger log = LoggerFactory.getLogger(DiscordUserClient.class);
    private static final String BASE = "https://discord.com/api/v10";
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final HttpClient http;
    private final HttpClient pollHttp;  // 轮询专用，短超时
    private final ObjectMapper mapper = new ObjectMapper();

    public DiscordUserClient(
            @Value("${discord.proxy.host:}") String proxyHost,
            @Value("${discord.proxy.port:0}") int proxyPort) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
        HttpClient.Builder pollBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));
        try {
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(null, null, new SecureRandom());
            builder.sslContext(ctx);
            pollBuilder.sslContext(ctx);
            log.info("DiscordUserClient SSLContext: TLSv1.3");
        } catch (Exception e) {
            log.warn("TLSv1.3 初始化失败，尝试 TLS: {}", e.getMessage());
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, null, new SecureRandom());
                builder.sslContext(ctx);
                pollBuilder.sslContext(ctx);
            } catch (Exception e2) {
                log.warn("自定义 SSLContext 初始化失败，使用默认: {}", e2.getMessage());
            }
        }
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
            pollBuilder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
            log.info("DiscordUserClient 使用代理: {}:{}", proxyHost, proxyPort);
        } else {
            log.info("DiscordUserClient 直连（未配置代理）");
        }
        this.http = builder.build();
        this.pollHttp = pollBuilder.build();
    }

    public JsonNode getMe(String token) throws Exception {
        return request(token, "GET", "/users/@me", null);
    }

    public List<JsonNode> listFriends(String token) throws Exception {
        return listRelationshipsByType(token, 1);
    }

    /**
     * 获取好友列表及对应原生 Presence。
     * GET /users/@me/relationships，返回 type=1 的好友关系，含 presence 字段。
     * 每个元素结构：{id, type, user:{id, username, ...}, presence:{status, desktop, mobile, web, activities}}
     */
    public JsonNode listFriendsWithPresence(String token) throws Exception {
        JsonNode arr = request(token, "GET", "/users/@me/relationships", null);
        if (arr == null || !arr.isArray()) return mapper.createArrayNode();
        com.fasterxml.jackson.databind.node.ArrayNode result = mapper.createArrayNode();
        for (JsonNode rel : arr) {
            int type = rel.path("type").asInt(0);
            if (type == 1) {
                result.add(rel);
            }
        }
        return result;
    }

    public List<JsonNode> listPendingFriendRequests(String token) throws Exception {
        return listRelationshipsByType(token, 3);
    }

    public void acceptFriendRequest(String token, String userId) throws Exception {
        requestNoBody(token, "PUT", "/users/@me/relationships/" + userId);
    }

    public void removeRelationship(String token, String userId) throws Exception {
        requestNoBody(token, "DELETE", "/users/@me/relationships/" + userId);
    }

    private List<JsonNode> listRelationshipsByType(String token, int targetType) throws Exception {
        JsonNode arr = request(token, "GET", "/users/@me/relationships", null);
        List<JsonNode> result = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode rel : arr) {
                int type = rel.path("type").asInt(0);
                if (type == targetType) {
                    JsonNode user = rel.path("user");
                    if (!user.isMissingNode()) result.add(user);
                }
            }
        }
        return result;
    }

    private void requestNoBody(String token, String method, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", token)
                .header("User-Agent", UA)
                .header("Accept", "application/json");
        b.method(method, HttpRequest.BodyPublishers.noBody());

        Exception lastException = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200 || code == 201 || code == 204) {
                    return;
                }
                if (code == 429) {
                    String retryAfter = resp.headers().firstValue("Retry-After").orElse("5");
                    Thread.sleep(Long.parseLong(retryAfter) * 1000L);
                    continue;
                }
                throw new DiscordUserApiException(code, resp.body());
            } catch (Exception e) {
                lastException = e;
                if (attempt < 2) {
                    Thread.sleep(1000L * (attempt + 1));
                }
            }
        }
        String errMsg = lastException != null ? lastException.getClass().getSimpleName() + ": " + lastException.getMessage() : "timeout";
        throw new RuntimeException("Discord API 调用失败（重试3次后仍失败）: " + errMsg, lastException);
    }

    public String openDmChannel(String token, String targetUserId) throws Exception {
        String body = mapper.writeValueAsString(Map.of("recipients", List.of(targetUserId)));
        JsonNode resp = request(token, "POST", "/users/@me/channels", body);
        return resp.path("id").asText(null);
    }

    public String sendMessage(String token, String channelId, String content) throws Exception {
        String body = mapper.writeValueAsString(Map.of("content", content));
        JsonNode resp = request(token, "POST", "/channels/" + channelId + "/messages", body);
        return resp.path("id").asText(null);
    }

    /**
     * 发送Discord原生Sticker消息，Discord会自动渲染为动画。
     * stickerId从URL中提取，如 https://cdn.discordapp.com/stickers/{stickerId}?format=json
     */
    public JsonNode sendStickerMessage(String token, String channelId, String stickerId) throws Exception {
        Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
        bodyMap.put("content", "");
        bodyMap.put("sticker_ids", java.util.List.of(stickerId));
        String body = mapper.writeValueAsString(bodyMap);
        return request(token, "POST", "/channels/" + channelId + "/messages", body);
    }

    /**
     * 发送带文件附件的消息（用于语音消息等）。
     * 使用 multipart/form-data 格式上传文件到 Discord。
     *
     * @param durationSecs 可选，语音消息时长（秒），用于 Discord 原生语音条展示
     * @param waveformBase64 可选，语音消息 waveform（Discord 客户端展示的波形条），传 null 则自动生成
     */
    public JsonNode sendMessageWithFile(String token, String channelId, String content,
                                          String fileName, byte[] fileData, String mimeType,
                                          Integer durationSecs, String waveformBase64) throws Exception {
        String boundary = "----DiscordAdminBoundary" + System.currentTimeMillis();
        StringBuilder bodyBuilder = new StringBuilder();

        // 1) 构造 payload_json，对语音消息写入 attachments[0].duration_secs / waveform / description / content 空
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        // 原生语音消息 content 为空，客户端会自动渲染语音条；非语音保留原始 content
        payload.put("content", content != null ? content : "");
        Map<String, Object> att0 = new java.util.LinkedHashMap<>();
        att0.put("id", "0");
        att0.put("filename", fileName);
        if (mimeType != null) att0.put("content_type", mimeType);
        att0.put("size", fileData.length);
        if (durationSecs != null) {
            att0.put("duration_secs", durationSecs);
            // 原生语音消息附件的描述字段，非必须但尽量带上
            att0.put("description", "Voice message");
        }
        // waveform: Discord 用的是 base64 编码的 256 字节 byte 数组，每个字节 0~31
        String wf = waveformBase64;
        if (wf == null && durationSecs != null && durationSecs > 0) {
            wf = generateDefaultWaveform(durationSecs);
        }
        if (wf != null) {
            att0.put("waveform", wf);
        }
        payload.put("attachments", List.of(att0));
        String payloadJson = mapper.writeValueAsString(payload);

        // 2) multipart: 先放 files[0] 文件二进制（Discord 要求文件 part 名称必须是 files[N]，不是 attachments[N]）
        bodyBuilder.append("--").append(boundary).append("\r\n");
        bodyBuilder.append("Content-Disposition: form-data; name=\"files[0]\"; filename=\"")
                .append(fileName).append("\"\r\n");
        bodyBuilder.append("Content-Type: ").append(mimeType != null ? mimeType : "application/octet-stream").append("\r\n\r\n");

        String bodyPart = bodyBuilder.toString();
        String endPart = "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + payloadJson + "\r\n"
                + "--" + boundary + "--\r\n";

        byte[] bodyBytes = concatenate(bodyPart.getBytes("UTF-8"), fileData, endPart.getBytes("UTF-8"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/channels/" + channelId + "/messages"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", token)
                .header("User-Agent", UA)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .header("X-Discord-Locale", "zh-CN")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();

        Exception lastException = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200 || code == 201) {
                    return mapper.readTree(resp.body());
                }
                if (code == 429) {
                    String retryAfter = resp.headers().firstValue("Retry-After").orElse("5");
                    Thread.sleep(Long.parseLong(retryAfter) * 1000L);
                    continue;
                }
                log.warn("sendMessageWithFile 非 2xx code={} body={}", code, resp.body());
                throw new DiscordUserApiException(code, resp.body());
            } catch (DiscordUserApiException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
                if (attempt < 2) {
                    Thread.sleep(1000L * (attempt + 1));
                }
            }
        }
        throw new RuntimeException("Discord 文件上传失败: " + (lastException != null ? lastException.getMessage() : "unknown"));
    }

    /**
     * 兼容旧签名（无 duration / waveform 参数）。
     */
    public JsonNode sendMessageWithFile(String token, String channelId, String content,
                                          String fileName, byte[] fileData, String mimeType) throws Exception {
        return sendMessageWithFile(token, channelId, content, fileName, fileData, mimeType, null, null);
    }

    /**
     * 生成 Discord 语音消息默认 waveform：256 字节，每字节 8~25 随机，编码 base64。
     * 让客户端至少能显示一个真实的波形，而不是空 / 报错。
     */
    private String generateDefaultWaveform(int durationSecs) {
        int len = 256;
        byte[] data = new byte[len];
        // 用时长作为种子，避免同一条消息多次发送波形完全不一致
        java.util.Random rnd = new java.util.Random(1000L * durationSecs + 7);
        for (int i = 0; i < len; i++) {
            // 模拟真实语音波形：中间高两端低
            double t = (double) i / len;
            double envelope = Math.sin(Math.PI * t) * 0.6 + 0.4;
            int v = (int) (8 + (17 * envelope) + rnd.nextInt(5) - 2);
            if (v < 5) v = 5;
            if (v > 31) v = 31;
            data[i] = (byte) v;
        }
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    private byte[] concatenate(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] arr : arrays) totalLength += arr.length;
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] arr : arrays) {
            System.arraycopy(arr, 0, result, offset, arr.length);
            offset += arr.length;
        }
        return result;
    }

    public JsonNode listMessages(String token, String channelId, int limit) throws Exception {
        return pollRequest(token, "/channels/" + channelId + "/messages?limit=" + Math.min(limit, 100));
    }

    /**
     * 增量拉取：获取指定消息ID之后的新消息。
     * 用于轮询场景，只拉取上次处理位置之后的新消息，避免重复拉取全量数据。
     * 使用短超时（5s）和快速重试，防止线程池被阻塞。
     *
     * @param afterMsgId 上次已处理的最新消息ID（Discord snowflake ID）
     * @param limit 最大消息数（最多100）
     */
    public JsonNode listMessagesAfter(String token, String channelId, String afterMsgId, int limit) throws Exception {
        if (afterMsgId == null || afterMsgId.isBlank()) {
            return listMessages(token, channelId, limit);
        }
        String path = "/channels/" + channelId + "/messages?after=" + afterMsgId + "&limit=" + Math.min(limit, 100);
        return pollRequest(token, path);
    }

    /**
     * 分页拉取历史消息：拉取指定消息ID之前的消息（向前翻页）。
     * Discord API返回的消息按时间倒序排列（最新在前），before参数表示拉取该ID之前的消息。
     */
    public JsonNode listMessagesBefore(String token, String channelId, String beforeMsgId, int limit) throws Exception {
        String path = "/channels/" + channelId + "/messages?limit=" + Math.min(limit, 100) + "&before=" + beforeMsgId;
        return pollRequest(token, path);
    }

    /**
     * 轮询专用请求：适中超时（8s）、快速重试（500ms），防止阻塞轮询线程池。
     * 仅用于消息拉取等对实时性要求高的场景。
     */
    private JsonNode pollRequest(String token, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", token)
                .header("User-Agent", UA)
                .header("Accept", "application/json");
        b.method("GET", HttpRequest.BodyPublishers.noBody());

        Exception lastException = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<String> resp = pollHttp.send(b.build(), HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200 || code == 201) {
                    return mapper.readTree(resp.body());
                }
                if (code == 429) {
                    String retryAfter = resp.headers().firstValue("Retry-After").orElse("3");
                    Thread.sleep(Long.parseLong(retryAfter) * 500L);  // 轮询用更短的等待
                    continue;
                }
                // 4xx 错误（非429）不可重试
                throw new DiscordUserApiException(code, resp.body());
            } catch (DiscordUserApiException e) {
                throw e;  // 业务错误直接抛出
            } catch (Exception e) {
                lastException = e;
                if (attempt < 1) {
                    Thread.sleep(500L);  // 轮询用500ms快速重试
                }
            }
        }
        String errMsg = lastException != null ? lastException.getClass().getSimpleName() + ": " + lastException.getMessage() : "unknown";
        log.warn("轮询请求失败: path={}, err={}", path, errMsg);
        throw new RuntimeException("轮询API调用失败: " + path + " - " + errMsg, lastException);
    }

    /**
     * 用邮箱密码登录Discord获取User Token（适用于未开启2FA的账号）。
     */
    public JsonNode login(String email, String password) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "login", email,
                "password", password,
                "undelete", false
        ));
        return requestNoToken("POST", "/auth/login", body);
    }

    /**
     * 用MFA ticket和code完成二次验证。
     */
    public JsonNode verifyMfa(String ticket, String code) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "ticket", ticket,
                "code", code
        ));
        return requestNoToken("POST", "/auth/mfa/verify", body);
    }

    /**
     * 列出当前 USER 账号的所有 DM 频道（GET /users/@me/channels）。
     * 返回 JsonNode 数组，每个元素含 {id, type, recipients:[{id, username, global_name, avatar}]}。
     * 用于同步 DM 频道为 Conversation，使消息轮询器能拉取到好友私信。
     * 使用短超时（5s）和快速重试，防止阻塞轮询主流程。
     */
    public JsonNode listDmChannels(String token) throws Exception {
        return pollRequest(token, "/users/@me/channels");
    }

    /**
     * 列出当前 USER 账号加入的所有服务器（Guild）。
     * GET /users/@me/guilds，返回 JsonNode 数组，每个元素含 {id, name, icon, owner, permissions}。
     * limit 默认 200。
     */
    public JsonNode listGuilds(String token) throws Exception {
        return request(token, "GET", "/users/@me/guilds?limit=200", null);
    }

    /**
     * 获取单个服务器（Guild）的详细信息。
     * GET /guilds/{guildId}，返回含 {id, name, icon, owner, member_count, ...} 的对象。
     * 用于在解析链接时获取服务器名称等信息。
     */
    public JsonNode getGuild(String token, String guildId) throws Exception {
        return request(token, "GET", "/guilds/" + guildId, null);
    }

    /**
     * 分页列出指定服务器的成员列表。
     * GET /guilds/{guildId}/members?limit=1000&after={lastUserId}
     * User Token 调用此接口需有相应权限（如管理员）。
     */
    public JsonNode listGuildMembers(String token, String guildId, int limit, String afterUserId) throws Exception {
        StringBuilder path = new StringBuilder("/guilds/")
                .append(guildId)
                .append("/members?limit=")
                .append(Math.min(limit, 1000));
        if (afterUserId != null && !afterUserId.isBlank()) {
            path.append("&after=").append(afterUserId);
        }
        return request(token, "GET", path.toString(), null);
    }

    private JsonNode request(String token, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", token)
                .header("User-Agent", UA)
                .header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }

        Exception lastException = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200 || code == 201) {
                    return mapper.readTree(resp.body());
                }
                if (code == 429) {
                    String retryAfter = resp.headers().firstValue("Retry-After").orElse("5");
                    Thread.sleep(Long.parseLong(retryAfter) * 1000L);
                    continue;
                }
                // 4xx 错误（非429）不可重试，直接抛出
                throw new DiscordUserApiException(code, resp.body());
            } catch (DiscordUserApiException e) {
                // Discord API 业务错误直接抛出，不重试
                throw e;
            } catch (Exception e) {
                // 网络/IO异常才重试
                lastException = e;
                if (attempt < 2) {
                    Thread.sleep(1000L * (attempt + 1));
                }
            }
        }
        String errMsg = lastException != null ? lastException.getClass().getSimpleName() + ": " + lastException.getMessage() : "timeout";
        throw new RuntimeException("Discord API 调用失败（重试3次后仍失败）: " + errMsg, lastException);
    }

    /**
     * 不带Token的请求（用于登录/MFA验证）。
     */
    private JsonNode requestNoToken(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }

        Exception lastException = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200 || code == 201) {
                    return mapper.readTree(resp.body());
                }
                if (code == 429) {
                    String retryAfter = resp.headers().firstValue("Retry-After").orElse("5");
                    log.info("Discord API rate limited (429), retrying after {}s (attempt {}/3)", retryAfter, attempt + 1);
                    Thread.sleep(Long.parseLong(retryAfter) * 1000L);
                    continue;
                }
                // 4xx 错误（非429）不可重试，直接抛出
                throw new DiscordUserApiException(code, resp.body());
            } catch (DiscordUserApiException e) {
                // Discord API 业务错误直接抛出，不重试
                throw e;
            } catch (Exception e) {
                // 网络/IO异常才重试
                lastException = e;
                if (attempt < 2) {
                    Thread.sleep(1000L * (attempt + 1));
                }
            }
        }
        throw new RuntimeException("Discord API 调用失败（重试3次后仍失败）: "
                + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
    }

    /**
     * 下载任意 URL 的内容并返回原始字节（复用 http 客户端与代理配置）。
     * 用于轮询消息时把 Discord CDN 的语音附件下载到本地转 base64，
     * 避免前端浏览器 <audio> 直接拉 CDN 时被 Referer/CORS 拦截。
     */
    public byte[] downloadBytes(String urlStr) throws Exception {
        if (urlStr == null || urlStr.isBlank()) throw new IllegalArgumentException("URL 为空");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                // CDN.discordapp.com 不校验 Referer，但设置为 discordapp.com 更保险
                .header("Referer", "https://discord.com/")
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("下载失败 HTTP " + resp.statusCode() + " url=" + urlStr);
        }
        return resp.body();
    }

    /**
     * 下载并编码为 Base64（用于后端入库，前端直接 data URL 播放）。
     * 失败返回 null（此时前端退化为用 audioUrl 尝试直连）。
     */
    public String downloadAsBase64(String urlStr) {
        try {
            byte[] bytes = downloadBytes(urlStr);
            if (bytes == null || bytes.length == 0) return null;
            return java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.warn("downloadAsBase64 失败: url={} err={}", urlStr == null ? "null" : urlStr, e.getMessage());
            return null;
        }
    }

    public static class DiscordUserApiException extends RuntimeException {
        public final int statusCode;
        public final String rawBody;
        public DiscordUserApiException(int statusCode, String rawBody) {
            super("Discord API " + statusCode + ": " + truncate(rawBody, 300));
            this.statusCode = statusCode;
            this.rawBody = rawBody;
        }
        private static String truncate(String s, int n) {
            if (s == null) return "";
            return s.length() <= n ? s : s.substring(0, n);
        }
    }
}
