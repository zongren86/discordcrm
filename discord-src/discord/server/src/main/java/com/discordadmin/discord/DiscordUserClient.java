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
    private final ObjectMapper mapper = new ObjectMapper();

    public DiscordUserClient(
            @Value("${discord.proxy.host:}") String proxyHost,
            @Value("${discord.proxy.port:0}") int proxyPort) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            builder.sslContext(ctx);
        } catch (Exception e) {
            log.warn("自定义 SSLContext 初始化失败，使用默认: {}", e.getMessage());
        }
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
            log.info("DiscordUserClient 使用代理: {}:{}", proxyHost, proxyPort);
        } else {
            log.info("DiscordUserClient 直连（未配置代理）");
        }
        this.http = builder.build();
    }

    public JsonNode getMe(String token) throws Exception {
        return request(token, "GET", "/users/@me", null);
    }

    public List<JsonNode> listFriends(String token) throws Exception {
        return listRelationshipsByType(token, 1);
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
        throw new RuntimeException("Discord API 调用失败（重试3次后仍失败）: "
                + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
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

    public JsonNode listMessages(String token, String channelId, int limit) throws Exception {
        return request(token, "GET", "/channels/" + channelId + "/messages?limit=" + Math.min(limit, 100), null);
    }

    /**
     * 分页拉取历史消息：拉取指定消息ID之前的消息（向前翻页）。
     * Discord API返回的消息按时间倒序排列（最新在前），before参数表示拉取该ID之前的消息。
     */
    public JsonNode listMessagesBefore(String token, String channelId, String beforeMsgId, int limit) throws Exception {
        String path = "/channels/" + channelId + "/messages?limit=" + Math.min(limit, 100) + "&before=" + beforeMsgId;
        return request(token, "GET", path, null);
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
     */
    public JsonNode listDmChannels(String token) throws Exception {
        return request(token, "GET", "/users/@me/channels", null);
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
        throw new RuntimeException("Discord API 调用失败（重试3次后仍失败）: "
                + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
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
