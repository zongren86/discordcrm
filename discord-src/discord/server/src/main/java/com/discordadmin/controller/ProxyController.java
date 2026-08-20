package com.discordadmin.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * 外部资源代理控制器
 * 
 * 解决外部 GIF/视频等资源的跨域加载问题。
 * Discord 客户端发送的动画 GIF 常托管在第三方域名（klipy.com, tenor.com, giphy.com 等），
 * 浏览器直接加载会被 CORS 策略阻止（ERR_BLOCKED_BY_RESPONSE.NotSameOrigin）。
 * 通过后端代理转发请求，绕过浏览器同源策略限制。
 */
@RestController
@RequestMapping("/api/proxy")
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    /** 允许代理的白名单域名（GIF/动画相关） */
    private static final Set<String> ALLOWED_DOMAINS = Set.of(
            "klipy.com",
            "tenor.com",
            "giphy.com",
            "imgur.com",
            "i.imgur.com",
            "cdn.discordapp.com",
            "media.discordapp.net",
            "discord.com",
            "cdn.klipy.com",
            "cdn.futuri.io",
            "futuri.io",
            "preview.redd.it",
            "i.redd.it",
            "v.redd.it",
            "i.gyazo.com",
            "cdn.gyazo.com",
            "v.eezee.me",
            "s.4cdn.org",
            "i.4cdn.org",
            "video.twimg.com",
            "pbs.twimg.com",
            "cdn.twitter.com",
            "scontent.cdninstagram.com",
            "cdninstagram.com"
    );

    /** 需要代理的外部域名特征 */
    private static final Set<String> EXTERNAL_DOMAINS = Set.of(
            "klipy.com",
            "tenor.com",
            "giphy.com",
            "imgur.com",
            "futuri.io",
            "gyazo.com",
            "4cdn.org",
            "redd.it",
            "twitter.com",
            "twimg.com",
            "instagram.com"
    );

    private final HttpClient httpClient;

    public ProxyController() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 代理获取外部资源
     * 
     * @param url 目标资源的完整 URL
     * @return 资源内容和正确的 Content-Type
     */
    @GetMapping("/fetch")
    public ResponseEntity<byte[]> fetch(@RequestParam("url") String url) {
        try {
            // 1. 验证 URL
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return ResponseEntity.badRequest().body("只支持 HTTP/HTTPS 协议".getBytes());
            }

            // 2. 域名白名单校验
            String host = uri.getHost();
            if (host == null || !isAllowedDomain(host)) {
                log.warn("代理请求被拒绝：非法域名 {}", host);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("域名不在白名单中".getBytes());
            }

            // 3. 构建请求 - 使用真实浏览器 User-Agent 避免被拦截
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "image/gif,image/webp,image/apng,image/*,video/mp4,video/webm,*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", url)
                    .GET()
                    .build();

            // 4. 执行请求
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 5. 检查是否被 Cloudflare 等拦截
            int statusCode = response.statusCode();
            String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            byte[] body = response.body();

            // 检测 Cloudflare 拦截页面
            if (statusCode == 403 && contentType.contains("text/html") && isCloudflareBlocked(body)) {
                log.warn("代理请求被 Cloudflare 拦截：{}", url);
                // 返回 424 Failed Dependency 让前端知道需要回退
                return ResponseEntity.status(424)
                        .header("X-Proxy-Error", "cloudflare-blocked")
                        .body("资源被Cloudflare防护拦截".getBytes());
            }

            // 6. 构建响应
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            
            // 设置缓存控制，减少重复请求
            headers.setCacheControl("public, max-age=3600");
            
            // 对 GIF/视频禁用 Range 请求支持（避免浏览器缓存不完整）
            headers.set("Accept-Ranges", "none");

            log.debug("代理请求成功：{} -> {} ({} bytes)", url, contentType, body.length);

            return new ResponseEntity<>(body, headers, statusCode);

        } catch (IOException | InterruptedException e) {
            log.error("代理请求失败：{} - {}", url, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(("代理请求失败: " + e.getMessage()).getBytes());
        } catch (Exception e) {
            log.error("代理请求异常：{} - {}", url, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("请求异常: " + e.getMessage()).getBytes());
        }
    }

    /**
     * 检测是否为 Cloudflare 拦截页面
     */
    private boolean isCloudflareBlocked(byte[] body) {
        if (body == null || body.length < 100) return false;
        String content = new String(body);
        return content.contains("Cloudflare") || 
               content.contains("challenge-platform") ||
               content.contains("Just a moment") ||
               content.contains("cf_chl_");
    }

    /**
     * 判断是否为需要代理的外部 GIF/视频 URL
     * 供前端判断使用
     */
    @GetMapping("/needs-proxy")
    public ResponseEntity<java.util.Map<String, Object>> needsProxy(@RequestParam("url") String url) {
        boolean needsProxy = isExternalGifUrl(url);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("needsProxy", needsProxy);
        if (needsProxy) {
            result.put("proxyUrl", "/api/proxy/fetch?url=" + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 检查域名是否在白名单中
     */
    private boolean isAllowedDomain(String host) {
        String lowerHost = host.toLowerCase();
        // 直接匹配
        if (ALLOWED_DOMAINS.contains(lowerHost)) {
            return true;
        }
        // 子域名匹配
        for (String domain : ALLOWED_DOMAINS) {
            if (lowerHost.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 URL 是否为外部 GIF/动画资源（需要代理）
     */
    private boolean isExternalGifUrl(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return false;
            String lowerHost = host.toLowerCase();
            
            // 检查是否为外部 GIF 域名
            for (String domain : EXTERNAL_DOMAINS) {
                if (lowerHost.equals(domain) || lowerHost.endsWith("." + domain)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
