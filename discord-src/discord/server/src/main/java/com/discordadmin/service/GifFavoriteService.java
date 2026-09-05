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

    // ====== 查询 ======

    /**
     * 按当前登录用户查收藏：
     * - 平台管理员（merchantId=null）→ 看全部
     * - 普通用户 → userId 匹配 OR merchantId 匹配（兜底历史无 userId 的收藏）
     */
    @Transactional(readOnly = true)
    public List<GifFavorite> getFavoritesForUser(Long userId, Long merchantId) {
        if (merchantId == null) {
            // 平台管理员 → 全部收藏
            return gifFavoriteRepository.findAllByOrderByCreatedAtDesc();
        }
        if (userId == null) {
            return gifFavoriteRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
        }
        // 普通用户 → userId OR merchantId 都匹配
        return gifFavoriteRepository.findByUserIdOrMerchantId(userId, merchantId);
    }

    @Transactional(readOnly = true)
    public List<GifFavorite> getFavoritesByUserId(Long userId) {
        return gifFavoriteRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<GifFavorite> getFavoritesByMerchant(Long merchantId) {
        return gifFavoriteRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    @Deprecated
    @Transactional(readOnly = true)
    public List<GifFavorite> getFavoritesByAccount(Long accountId) {
        return gifFavoriteRepository.findByDiscordAccountIdOrderByCreatedAtDesc(accountId);
    }

    // ====== 收藏 ======

    @Transactional
    public GifFavorite addFavorite(Long accountId, String gifUrl, String title, Long userId) {
        return addFavorite(accountId, gifUrl, title, "gif", null, userId);
    }

    @Transactional
    public GifFavorite addFavorite(Long accountId, String gifUrl, String title, String type, Long userId) {
        return addFavorite(accountId, gifUrl, title, type, null, userId);
    }

    /**
     * 添加收藏 —— 按 userId 去重（跨账号共享）。
     * discord_account_id 仅作来源记录，账号不存在/已删除都不影响收藏保存。
     */
    @Transactional
    public GifFavorite addFavorite(Long accountId, String gifUrl, String title, String type,
                                     String convertedGifUrl, Long userId) {
        String normalizedUrl = normalizeGifUrl(gifUrl);
        String urlHash = hashUrl(normalizedUrl);

        // 按 userId 查重（新逻辑：同一用户跨账号不重复收藏同一个）
        Optional<GifFavorite> existing = userId != null
                ? gifFavoriteRepository.findByUserIdAndGifUrlHash(userId, urlHash)
                : Optional.empty();
        if (existing.isPresent()) {
            GifFavorite fav = existing.get();
            if (convertedGifUrl != null && !convertedGifUrl.equals(fav.getConvertedGifUrl())) {
                fav.setConvertedGifUrl(convertedGifUrl);
                gifFavoriteRepository.save(fav);
            }
            log.info("已收藏: userId={}, url={}", userId, normalizedUrl);
            return fav;
        }

        // 账号可能已删除 —— Optional 获取
        Long resolvedAccountId = null;
        Long merchantId = null;
        if (accountId != null) {
            Optional<DiscordAccount> accOpt = discordAccountRepository.findById(accountId);
            if (accOpt.isPresent()) {
                DiscordAccount acc = accOpt.get();
                resolvedAccountId = acc.getId();
                merchantId = acc.getMerchantId();
            } else {
                log.info("收藏时账号已不存在 accountId={}，discord_account_id=null 继续保存", accountId);
            }
        }
        if (merchantId == null && userId != null) {
            try { merchantId = gifFavoriteRepository.findMerchantIdByUserId(userId).orElse(null); }
            catch (Exception ignored) {}
        }

        GifFavorite favorite = new GifFavorite();
        favorite.setUserId(userId);
        favorite.setDiscordAccountId(resolvedAccountId);
        favorite.setMerchantId(merchantId);
        favorite.setGifUrl(normalizedUrl);
        favorite.setGifUrlHash(urlHash);
        favorite.setTitle(title);
        favorite.setSourceDomain(extractDomain(normalizedUrl));
        favorite.setType(type != null ? type : "gif");
        favorite.setConvertedGifUrl(convertedGifUrl);

        GifFavorite saved = gifFavoriteRepository.save(favorite);
        log.info("收藏成功: id={}, userId={}, accountId={}, type={}",
                saved.getId(), userId, resolvedAccountId, type);
        return saved;
    }

    // ====== 取消收藏 ======

    @Transactional
    public void removeFavorite(Long id, Long accountId, Long userId) {
        removeFavoriteByUserId(id, userId);
    }

    @Transactional
    public void removeFavoriteByUserId(Long id, Long userId) {
        if (userId != null) {
            gifFavoriteRepository.deleteByIdAndUserId(id, userId);
        } else {
            gifFavoriteRepository.deleteById(id);
        }
        log.info("GIF 收藏已删除: id={}, userId={}", id, userId);
    }

    // ====== 检查是否已收藏 ======

    public boolean isFavorited(Long accountId, String gifUrl) {
        return isFavorited(accountId, null, gifUrl);
    }

    public boolean isFavorited(Long accountId, Long userId, String gifUrl) {
        String normalizedUrl = normalizeGifUrl(gifUrl);
        String urlHash = hashUrl(normalizedUrl);
        if (userId != null) {
            return gifFavoriteRepository.findByUserIdAndGifUrlHash(userId, urlHash).isPresent();
        }
        return gifFavoriteRepository.findByDiscordAccountIdAndGifUrlHash(accountId, urlHash).isPresent();
    }

    // ====== URL 工具 ======

    public String normalizeGifUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.contains("tenor.com/view/") && !url.matches(".*\\.(gif|mp4|webm|webp)(\\?|#|$).*")) {
            return url + ".gif";
        }
        return url;
    }

    private String hashUrl(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            log.error("计算 URL 哈希失败: {}", e.getMessage());
            return url;
        }
    }

    private String extractDomain(String url) {
        try {
            int start = url.indexOf("://");
            if (start == -1) return "";
            int end = url.indexOf("/", start + 3);
            return end == -1 ? url.substring(start + 3) : url.substring(start + 3, end);
        } catch (Exception e) {
            return "";
        }
    }
}
