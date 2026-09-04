package com.discordadmin.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
            "static2.klipy.com",
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

    private final HttpClient httpClient;  // 带代理的客户端
    private final HttpClient directHttpClient;  // 直连客户端（代理不可用时使用）
    private final boolean proxyConfigured;  // 是否已配置代理
    private volatile Boolean proxyAvailable = null;  // 代理可用性缓存
    private volatile long lastProxyCheckTime = 0;  // 上次检查代理时间

    public ProxyController(
            @Value("${discord.proxy.host:127.0.0.1}") String proxyHost,
            @Value("${discord.proxy.port:7890}") int proxyPort) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        
        // 创建直连客户端（始终可用）
        this.directHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        
        boolean proxyOk = false;
        try {
            // 配置代理
            java.net.InetSocketAddress proxyAddr = new java.net.InetSocketAddress(proxyHost, proxyPort);
            builder.proxy(java.net.ProxySelector.of(proxyAddr));
            log.info("ProxyController 配置代理: {}:{}", proxyHost, proxyPort);
            proxyOk = true;
        } catch (Exception e) {
            log.warn("ProxyController 代理配置失败，仅使用直连: {}", e.getMessage());
        }
        this.proxyConfigured = proxyOk;
        this.httpClient = builder.build();
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

            // 3. 构建请求 - 使用多组浏览器特征轮流使用
            String[] userAgents = {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15"
            };
            String userAgent = userAgents[(int)(System.currentTimeMillis() % userAgents.length)];

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent)
                    .header("Accept", "image/gif,image/webp,image/apng,image/*,video/mp4,video/webm,*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                    .header("Sec-Ch-Ua-Mobile", "?0")
                    .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                    .header("Sec-Fetch-Dest", "image")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", "cross-site")
                    .header("Referer", url)
                    .GET()
                    .build();

            // 4. 执行请求（带重试机制 + 代理降级）
            HttpResponse<byte[]> response = null;
            int maxRetries = 2;
            boolean useProxy = proxyConfigured && isProxyAvailable();
            
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                try {
                    // 根据代理可用性选择客户端
                    HttpClient client = useProxy ? httpClient : directHttpClient;
                    response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    
                    // 如果成功获取内容，立即返回
                    int statusCode = response.statusCode();
                    String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
                    byte[] body = response.body();

                    // 如果是图片/GIF/视频内容，即使状态码是403，也尝试返回（有些CDN会返回内容但状态码是403）
                    log.info("{}请求 attempt={} url={} status={} ct={} size={}",
                        useProxy ? "代理" : "直连", attempt+1, url, statusCode, contentType, body != null ? body.length : 0);

                    // 放宽成功判断: 200/204/206/301/302/403/404 只要有 body 就返回
                    if (statusCode == 200 || statusCode == 204 || statusCode == 206
                        || statusCode == 301 || statusCode == 302
                        || ((body != null && body.length > 0) && (statusCode == 403 || statusCode == 404))) {
                        
                        // 检测 Cloudflare 拦截页面
                        if (statusCode == 403 && contentType.contains("text/html") && isCloudflareBlocked(body)) {
                            log.warn("{}请求被 Cloudflare 拦截：{}", useProxy ? "代理" : "直连", url);
                            // 对于 Cloudflare 拦截，尝试用不同的 User-Agent 重试
                            if (attempt < maxRetries - 1) {
                                try { Thread.sleep(500); } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                continue;
                            }
                            // 返回 424 让前端显示原始 URL 链接
                            return ResponseEntity.status(424)
                                    .header("X-Proxy-Error", "cloudflare-blocked")
                                    .body("资源被Cloudflare防护拦截".getBytes());
                        }

                        // 成功获取内容
                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(MediaType.parseMediaType(contentType));
                        headers.setCacheControl("public, max-age=3600");
                        headers.set("Accept-Ranges", "none");

                        log.debug("{}请求成功：{} -> {} ({} bytes)", useProxy ? "代理" : "直连", url, contentType, body.length);
                        return new ResponseEntity<>(body, headers, statusCode);
                    }
                } catch (Exception e) {
                    log.warn("{}请求尝试 {} 异常：{} - {} ({})", useProxy ? "代理" : "直连", attempt + 1, url, e.getMessage(), e.getClass().getSimpleName());
                    
                    // 代理失败时，自动降级为直连
                    if (useProxy && attempt == 0) {
                        log.warn("代理请求失败，降级为直连请求：{}", url);
                        useProxy = false;
                        // 标记代理不可用
                        proxyAvailable = false;
                        lastProxyCheckTime = System.currentTimeMillis();
                        // 用直连重试
                        continue;
                    }
                    
                    if (attempt < maxRetries - 1) {
                        try { Thread.sleep(300); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }

            // 所有重试都失败了
            log.warn("请求最终失败（{}）：{}", useProxy ? "代理" : "直连", url);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .header("X-Proxy-Error", "request-failed")
                    .body(("资源加载失败，请稍后重试").getBytes());

        } catch (Exception e) {
            log.error("代理请求异常：{} - {}", url, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("请求异常: " + e.getMessage()).getBytes());
        }
    }

    /**
     * 检查内容是否为媒体资源（即使状态码是403也可能返回有效内容）
     */
    private boolean isMediaContent(String contentType, byte[] body) {
        if (contentType == null) return false;
        String lowerContentType = contentType.toLowerCase();
        // 检查是否为媒体类型
        if (lowerContentType.startsWith("image/") || 
            lowerContentType.startsWith("video/") || 
            lowerContentType.contains("gif") ||
            lowerContentType.contains("mp4") ||
            lowerContentType.contains("webm")) {
            return true;
        }
        // 检查内容是否为二进制（不是HTML）
        if (body != null && body.length > 0) {
            // 如果内容不是HTML，可能是有效的媒体资源
            String contentPreview = new String(body, 0, Math.min(body.length, 1000));
            if (!contentPreview.toLowerCase().contains("<html") && 
                !contentPreview.toLowerCase().contains("<!doctype")) {
                // 可能是有效的二进制内容
                byte firstByte = body[0];
                // GIF: 47 49 46 ("GIF")
                // PNG: 89 50 4e 47
                // JPEG: ff d8 ff
                // MP4: 00 00 00 (with ftyp)
                // WebM: 1a 45 df a3
                if (firstByte == 0x47 || firstByte == 0x89 || firstByte == (byte)0xFF || 
                    firstByte == 0x1a || firstByte == 0x00) {
                    return true;
                }
            }
        }
        return false;
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
     * 解析 GIF URL - 对于 klipy.com 等有 Cloudflare 防护的网站，
     * 使用 GoogleBot UA 访问页面提取真实的 CDN URL
     * 支持代理降级：代理不可用时自动使用直连
     */
    @GetMapping("/resolve-gif-url")
    public ResponseEntity<java.util.Map<String, Object>> resolveGifUrl(@RequestParam("url") String url) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) {
                result.put("success", false);
                result.put("error", "无效的 URL");
                return ResponseEntity.ok(result);
            }

            String lowerHost = host.toLowerCase();
            // 只处理 klipy.com 的 URL
            if (!lowerHost.equals("klipy.com") && !lowerHost.endsWith(".klipy.com")) {
                result.put("success", true);
                result.put("resolvedUrl", url);
                return ResponseEntity.ok(result);
            }

            // 使用 GoogleBot UA 访问 klipy 页面，支持代理降级
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Googlebot/2.1 (+http://www.google.com/bot.html)")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();

            // 先尝试代理，失败后降级为直连
            String pageContent = null;
            boolean useProxy = proxyConfigured && isProxyAvailable();
            
            if (useProxy) {
                try {
                    HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() == 200) {
                        pageContent = new String(response.body());
                        log.debug("解析 GIF URL: 代理请求成功 {}", url);
                    } else {
                        log.warn("解析 GIF URL: 代理请求返回状态码 {}", response.statusCode());
                    }
                } catch (Exception e) {
                    log.warn("解析 GIF URL: 代理请求失败，降级为直连: {}", e.getMessage());
                    useProxy = false;
                    // 标记代理不可用
                    proxyAvailable = false;
                    lastProxyCheckTime = System.currentTimeMillis();
                }
            }
            
            // 直连降级
            if (pageContent == null) {
                try {
                    HttpResponse<byte[]> response = directHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() == 200) {
                        pageContent = new String(response.body());
                        log.debug("解析 GIF URL: 直连请求成功 {}", url);
                    } else {
                        log.warn("解析 GIF URL: 直连请求返回状态码 {}", response.statusCode());
                    }
                } catch (Exception e) {
                    log.warn("解析 GIF URL: 直连请求也失败: {}", e.getMessage());
                }
            }

            if (pageContent != null) {
                // 从页面中提取媒体 URL（支持 .gif, .mp4, .webm 格式）
                String mediaUrl = extractMediaUrl(pageContent);
                if (mediaUrl != null) {
                    result.put("success", true);
                    result.put("resolvedUrl", mediaUrl);
                    result.put("originalUrl", url);
                    result.put("method", "googlebot-extract");
                } else {
                    // 提取失败，返回原始 URL 让前端尝试
                    result.put("success", true);
                    result.put("resolvedUrl", url);
                    result.put("originalUrl", url);
                    result.put("method", "fallback");
                }
            } else {
                // 两种方式都失败，返回原始 URL
                log.warn("解析 GIF URL: 代理和直连都失败，返回原始 URL");
                result.put("success", true);
                result.put("resolvedUrl", url);
                result.put("originalUrl", url);
                result.put("method", "direct-fallback");
            }

        } catch (Exception e) {
            log.error("解析 GIF URL 异常: {} - {}", url, e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 从 HTML 页面内容中提取媒体 URL
     * 优先提取 .gif，然后 .mp4/.webm
     */
    private String extractMediaUrl(String html) {
        if (html == null || html.isEmpty()) return null;

        // 匹配 https://static*.klipy.com/.../xxx.gif 或 .mp4 等
        // 先找 GIF URL
        java.util.regex.Pattern gifPattern = java.util.regex.Pattern.compile(
                "https?://[^\"'\\s<>]+\\.gif[^\"'\\s<>]*", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher gifMatcher = gifPattern.matcher(html);
        if (gifMatcher.find()) {
            String gifUrl = gifMatcher.group(0);
            // 清理可能的尾部字符
            gifUrl = gifUrl.replaceAll("[),;\\]]$", "");
            return gifUrl;
        }

        // 再找 MP4 URL
        java.util.regex.Pattern mp4Pattern = java.util.regex.Pattern.compile(
                "https?://[^\"'\\s<>]+\\.(mp4|webm)[^\"'\\s<>]*", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher mp4Matcher = mp4Pattern.matcher(html);
        if (mp4Matcher.find()) {
            String mp4Url = mp4Matcher.group(0);
            mp4Url = mp4Url.replaceAll("[),;\\]]$", "");
            return mp4Url;
        }

        return null;
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

    /**
     * 检测代理是否可用
     * 使用缓存机制，每 30 秒检查一次
     */
    private boolean isProxyAvailable() {
        // 如果没有配置代理，直接返回 false
        if (!proxyConfigured) return false;
        
        long now = System.currentTimeMillis();
        // 如果还在缓存有效期内，直接返回缓存结果
        if (proxyAvailable != null && (now - lastProxyCheckTime) < 30_000) {
            return proxyAvailable;
        }
        
        // 执行实际检测
        try {
            // 尝试通过代理访问一个已知可用的 URL
            HttpRequest testRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://httpbin.org/ip"))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "ProxyChecker/1.0")
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(testRequest, HttpResponse.BodyHandlers.ofString());
            boolean available = response.statusCode() == 200;
            
            // 更新缓存
            proxyAvailable = available;
            lastProxyCheckTime = now;
            
            if (available) {
                log.info("代理服务器可用");
            } else {
                log.warn("代理服务器不可用，将使用直连模式");
            }
            return available;
        } catch (Exception e) {
            // 检测失败，标记为不可用
            proxyAvailable = false;
            lastProxyCheckTime = now;
            log.warn("代理服务器检测失败: {}，将使用直连模式", e.getMessage());
            return false;
        }
    }
}
