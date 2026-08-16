package com.discordadmin.discord;

import com.discordadmin.service.ConversationService;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.stream.Collectors;

/**
 * 每个被聚合的 Discord 账号拥有独立的 listener 实例，
 * 通过 accountId 标识消息来自哪个账号，便于 ConversationService 关联归属。
 */
public class DiscordMessageListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordMessageListener.class);

    private final Long accountId;
    private final ConversationService conversationService;

    public DiscordMessageListener(Long accountId, ConversationService conversationService) {
        this.accountId = accountId;
        this.conversationService = conversationService;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) {
            log.debug("收到消息但忽略：作者是bot或系统 (accountId={} authorId={})", accountId, event.getAuthor().getId());
            return;
        }

        var attachments = event.getMessage().getAttachments();
        String attachmentsJson = attachments.isEmpty()
                ? null
                : attachments.stream()
                        .map(a -> a.getFileName() + "(" + (a.getContentType() == null ? "no-mime" : a.getContentType()) + "," + a.getSize() + "B)")
                        .collect(Collectors.joining(", "));

        log.info("[DISCORD MSG IN] accountId={} discordMsgId={} isDM={} authorId={} author={} channelId={} attachments=[{}] contentPreview={}",
                accountId,
                event.getMessageId(),
                !event.isFromGuild(),
                event.getAuthor().getId(),
                event.getAuthor().getName(),
                event.getChannel().getId(),
                attachmentsJson == null ? "" : attachmentsJson,
                truncate(event.getMessage().getContentDisplay(), 120));

        String messageType = "text";
        String audioUrl = null;
        String audioMimeType = null;
        Integer audioDuration = null;
        String audioData = null;
        String content = event.getMessage().getContentDisplay();

        // 检测语音附件（Discord 语音消息通过 attachment 上传，文件名以 "voice-message" 开头）
        if (!attachments.isEmpty()) {
            Attachment voiceAtt = attachments.stream()
                    .filter(a -> (a.getContentType() != null && a.getContentType().startsWith("audio/"))
                            || (a.getFileName() != null && a.getFileName().startsWith("voice-message")))
                    .findFirst().orElse(null);
            if (voiceAtt != null) {
                try {
                    messageType = "voice";
                    audioUrl = voiceAtt.getUrl();
                    audioMimeType = resolveMimeType(voiceAtt);
                    // JDA 4.4.x 中 getDuration 返回类型不稳定，统一按 double 转 int，失败置空
                    Integer dur = resolveDuration(voiceAtt);
                    if (dur != null) audioDuration = dur;
                    log.info("[DISCORD VOICE] 识别为语音消息: accountId={} file={} mime={} size={}KB dur={}s url={}",
                            accountId, voiceAtt.getFileName(), audioMimeType, voiceAtt.getSize() / 1024, audioDuration,
                            truncate(audioUrl, 120));
                    audioData = downloadAndEncode(voiceAtt.getUrl());
                    if (audioData != null) {
                        log.info("[DISCORD VOICE] 本地下载完成: accountId={} file={} base64Len={}",
                                accountId, voiceAtt.getFileName(), audioData.length());
                    } else {
                        log.warn("[DISCORD VOICE] 本地下载失败（audioData为空），将仅走公网URL的异步策略: accountId={} url={}",
                                accountId, truncate(audioUrl, 120));
                    }
                    if (content == null || content.isBlank()) {
                        content = "[语音消息]";
                    }
                } catch (Exception ex) {
                    log.warn("下载语音附件失败: {}", ex.getMessage(), ex);
                }
            }
        }

        InboundMessage inbound = new InboundMessage(
                accountId,
                event.getMessageId(),
                event.getAuthor().getId(),
                event.getAuthor().getName(),
                event.getAuthor().getGlobalName(),
                event.getAuthor().getEffectiveAvatarUrl(),
                !event.isFromGuild(),
                event.isFromGuild() ? event.getGuild().getId() : null,
                event.isFromGuild() ? event.getGuild().getName() : null,
                event.getChannel().getId(),
                event.isFromGuild() ? event.getChannel().getName() : "私信",
                content,
                attachmentsJson,
                messageType,
                audioUrl,
                audioMimeType,
                audioDuration,
                audioData
        );

        try {
            conversationService.handleInbound(inbound);
        } catch (Exception e) {
            log.error("处理入站Discord消息失败 (accountId={})", accountId, e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String resolveMimeType(Attachment a) {
        if (a.getContentType() != null) return a.getContentType();
        String fn = a.getFileName() == null ? "" : a.getFileName().toLowerCase();
        if (fn.endsWith(".ogg")) return "audio/ogg";
        if (fn.endsWith(".mp3")) return "audio/mpeg";
        if (fn.endsWith(".wav")) return "audio/wav";
        if (fn.endsWith(".m4a")) return "audio/mp4";
        if (fn.endsWith(".webm")) return "audio/webm";
        return "audio/ogg";
    }

    /**
     * 兼容不同 JDA 版本的 Attachment#getDuration：
     * - JDA 5.x: 返回 Duration 对象（有 getSeconds 方法）
     * - 部分版本: 返回 double（秒数）
     * 都用反射拿，失败返回 null。
     */
    private Integer resolveDuration(Attachment a) {
        try {
            java.lang.reflect.Method m = a.getClass().getMethod("getDuration");
            Object val = m.invoke(a);
            if (val == null) return null;
            if (val instanceof java.time.Duration d) {
                long s = d.getSeconds();
                return s > 0 ? (int) s : (int) Math.ceil(d.toNanos() / 1_000_000_000.0);
            }
            if (val instanceof Number n) {
                return (int) Math.max(1, Math.ceil(n.doubleValue()));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String downloadAndEncode(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            try (InputStream is = conn.getInputStream()) {
                byte[] bytes = is.readAllBytes();
                return Base64.getEncoder().encodeToString(bytes);
            }
        } catch (Exception e) {
            log.warn("下载音频数据失败: {}", e.getMessage());
            return null;
        }
    }
}
