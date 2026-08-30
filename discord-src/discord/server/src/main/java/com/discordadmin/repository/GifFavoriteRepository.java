package com.discordadmin.repository;

import com.discordadmin.entity.GifFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * GIF 收藏 Repository
 */
@Repository
public interface GifFavoriteRepository extends JpaRepository<GifFavorite, Long> {

    /**
     * 按 Discord 账号查询收藏列表（按收藏时间降序）
     */
    List<GifFavorite> findByDiscordAccountIdOrderByCreatedAtDesc(Long discordAccountId);

    /**
     * 按商户ID查询收藏列表
     */
    List<GifFavorite> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    /**
     * 按账号ID和URL哈希查询是否已收藏
     */
    Optional<GifFavorite> findByDiscordAccountIdAndGifUrlHash(Long discordAccountId, String gifUrlHash);

    /**
     * 按账号ID删除收藏
     */
    void deleteByDiscordAccountId(Long discordAccountId);

    /**
     * 按账号ID删除收藏
     */
    void deleteByIdAndDiscordAccountId(Long id, Long discordAccountId);

    /**
     * 统计账号收藏数量
     */
    long countByDiscordAccountId(Long discordAccountId);

    // ====== 基于 userId（当前登录用户）的查询 ======

    /**
     * 按当前登录用户查询收藏列表
     */
    List<GifFavorite> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 按 userId + URL 查重
     */
    Optional<GifFavorite> findByUserIdAndGifUrlHash(Long userId, String gifUrlHash);

    /**
     * 按 userId 查重：先 Discord 账号维度，再 userId 维度
     * （兼容旧数据迁移前的场景）
     */
    default Optional<GifFavorite> findByAccountOrUser(Long accountId, Long userId, String gifUrlHash) {
        // 优先按 userId + hash 查（新逻辑）
        Optional<GifFavorite> byUser = findByUserIdAndGifUrlHash(userId, gifUrlHash);
        if (byUser.isPresent()) return byUser;
        // 兜底按 accountId + hash 查（旧数据）
        return findByDiscordAccountIdAndGifUrlHash(accountId, gifUrlHash);
    }
}
