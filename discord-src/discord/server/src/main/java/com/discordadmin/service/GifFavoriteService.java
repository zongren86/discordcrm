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

    @Transactional(readOnly = true)
    public List<GifFavorite> getFavoritesByAccount(Long accountId) {
        return gifFavoriteRepository.findByDiscordAccountIdOrderByCreatedAtDesc(accountId);
    }

    @Transactional(readOnly = true)
    public List<GifFavorite> getFavoritesByMerchant(Long merchantId) {
        return gifFavoriteRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    @Transactional
    public GifFavorite addFavorite(Long accountId, String gifUrl, String title) {
        return addFavorite(accountId, gifUrl, title, "gif", null);
    }

    @Transactional
    public GifFavorite addFavorite(Long accountId, String gifUrl, String title, String type) {
        return addFavorite(accountId, gifUrl, title, type, null);
    }

    @Transactional
    public GifFavorite addFavorite(Long accountId, String gifUrl, String title, String type, String convertedGifUrl) {
        String normalizedUrl = normalizeGifUrl(gifUrl);
        String urlHash = hashUrl(normalizedUrl);
        
        Optional<GifFavorite> existing = gifFavoriteRepository
                .findByDiscordAccountIdAndGifUrlHash(accountId, urlHash);
        if (existing.isPresent()) {
            GifFavorite fav = existing.get();
            // 如果已有 convertedGifUrl 且新的也有，更新它
            if (convertedGifUrl != null && !convertedGifUrl.equals(fav.getConvertedGifUrl())) {
                fav.setConvertedGifUrl(convertedGifUrl);
                gifFavoriteRepository.save(fav);
                log.info("更新 convertedGifUrl: id={}", fav.getId());
            }
            log.info("已收藏: accountId={}, url={}", accountId, normalizedUrl);
            return fav;
        }
        
        DiscordAccount account = discordAccountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在: " + accountId));
        
        String sourceDomain = extractDomain(normalizedUrl);
        
        GifFavorite favorite = new GifFavorite();
        favorite.setDiscordAccount(account);
        favorite.setMerchantId(account.getMerchantId());
        favorite.setGifUrl(normalizedUrl);
        favorite.setGifUrlHash(urlHash);
        favorite.setTitle(title);
        favorite.setSourceDomain(sourceDomain);
        favorite.setType(type != null ? type : "gif");
        favorite.setConvertedGifUrl(convertedGifUrl);
        
        GifFavorite saved = gifFavoriteRepository.save(favorite);
        log.info("收藏成功: id={}, accountId={}, url={}, type={}, hasConvertedGif={}", 
                saved.getId(), accountId, normalizedUrl, type, convertedGifUrl != null);
        
        return saved;
    }

    @Transactional
    public void removeFavorite(Long id, Long accountId) {
        gifFavoriteRepository.deleteByIdAndDiscordAccountId(id, accountId);
        log.info("GIF 收藏已删除: id={}, accountId={}", id, accountId);
    }

    @Transactional(readOnly = true)
    public boolean isFavorited(Long accountId, String gifUrl) {
        String normalizedUrl = normalizeGifUrl(gifUrl);
        String urlHash = hashUrl(normalizedUrl);
        return gifFavoriteRepository.findByDiscordAccountIdAndGifUrlHash(accountId, urlHash).isPresent();
    }

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
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
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
            if (end == -1) return url.substring(start + 3);
            return url.substring(start + 3, end);
        } catch (Exception e) {
            return "";
        }
    }
}
