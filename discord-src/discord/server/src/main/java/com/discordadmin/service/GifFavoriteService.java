package com.discordadmin.service;

import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.GifFavorite;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.GifFavoriteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * GIF 收藏 Service
 */
@Service
public class GifFavoriteService {

    private static final Logger log = LoggerFactory.getLogger(GifFavoriteService.class);

    private final GifFavoriteRepository gifFavoriteRepository;
    private final DiscordAccountRepository discordAccountRepository;

    public GifFavoriteService(GifFavoriteRepository gifFavoriteRepository,
                             DiscordAccountRepository discordAccountRepository) {
        this.gifFavoriteRepository = gifFavoriteRepository;
        this.discordAccountRepository = discordAccountRepository;
    }

    /**
     * 获取指定账号的 GIF 收藏列表
     */
    @Transactional(readOnly = true)
    public List<GifFavorite> getFavoritesByAccount(Long accountId) {
        return gifFavoriteRepository.findByDiscordAccountIdOrderByCreatedAtDesc(accountId);
    }

    /**
     * 获取指定商户的 GIF 收藏列表（管理员查看所有）
     */
    @Transactional(readOnly = true)
    public List<GifFavorite> getFavoritesByMerchant(Long merchantId) {
        return gifFavoriteRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    /**
     * 添加 GIF 收藏
     * 
     * @param accountId Discord 账号ID
     * @param gifUrl GIF URL（会自动规范化为可直接发送的 URL）
     * @param title 标题（可选）
     * @return 收藏记录，如果已存在则返回 null
     */
    @Transactional
    public GifFavorite addFavorite(Long accountId, String gifUrl, String title) {
        return addFavorite(accountId, gifUrl, title, "gif");
    }

    @Transactional
    public GifFavorite addFavorite(Long accountId, String gifUrl, String title, String type) {
        // 规范化 URL
        String normalizedUrl = normalizeGifUrl(gifUrl);
        
        // 计算 URL 哈希
        String urlHash = hashUrl(normalizedUrl);
        
        // 检查是否已收藏
        Optional<GifFavorite> existing = gifFavoriteRepository
                .findByDiscordAccountIdAndGifUrlHash(accountId, urlHash);
        if (existing.isPresent()) {
            log.info("已收藏: accountId={}, url={}", accountId, normalizedUrl);
            return existing.get();
        }
        
        // 获取账号信息
        DiscordAccount account = discordAccountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在: " + accountId));
        
        // 解析来源域名
        String sourceDomain = extractDomain(normalizedUrl);
        
        // 创建收藏记录
        GifFavorite favorite = new GifFavorite();
        favorite.setDiscordAccount(account);
        favorite.setMerchantId(account.getMerchantId());
        favorite.setGifUrl(normalizedUrl);
        favorite.setGifUrlHash(urlHash);
        favorite.setTitle(title);
        favorite.setSourceDomain(sourceDomain);
        favorite.setType(type != null ? type : "gif");
        
        GifFavorite saved = gifFavoriteRepository.save(favorite);
        log.info("收藏成功: id={}, accountId={}, url={}, type={}", saved.getId(), accountId, normalizedUrl, type);
        
        return saved;
    }

    /**
     * 删除 GIF 收藏
     */
    @Transactional
    public void removeFavorite(Long id, Long accountId) {
        gifFavoriteRepository.deleteByIdAndDiscordAccountId(id, accountId);
        log.info("GIF 收藏已删除: id={}, accountId={}", id, accountId);
    }

    /**
     * 检查是否已收藏
     */
    @Transactional(readOnly = true)
    public boolean isFavorited(Long accountId, String gifUrl) {
        String normalizedUrl = normalizeGifUrl(gifUrl);
        String urlHash = hashUrl(normalizedUrl);
        return gifFavoriteRepository.findByDiscordAccountIdAndGifUrlHash(accountId, urlHash).isPresent();
    }

    /**
     * 规范化 GIF URL
     * 将分享链接转换为可直接发送的 URL（确保对方 Discord 客户端能自动展开动画）
     */
    public String normalizeGifUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        
        // 处理 tenor.com 分享链接
        // https://tenor.com/view/xxx → https://tenor.com/view/xxx.gif
        if (url.contains("tenor.com/view/") && !url.matches(".*\\.(gif|mp4|webm|webp)(\\?|#|$).*")) {
            return url + ".gif";
        }
        
        return url;
    }

    /**
     * 计算 URL 的 SHA-256 哈希
     */
    private String hashUrl(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("计算 URL 哈希失败: {}", e.getMessage());
            return url; // 降级使用原始 URL
        }
    }

    /**
     * 提取域名
     */
    private String extractDomain(String url) {
        try {
            int start = url.indexOf("://");
            if (start == -1) return "";
            int end = url.indexOf("/", start + 3);
            if (end == -1) return url.substring(start + 3);
            return url.substring(start + 3, end);
        } catch (Exception e) {
            return "";
        }
    }
}
