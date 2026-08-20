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
    void deleteByIdAndDiscordAccountId(Long id, Long discordAccountId);

    /**
     * 统计账号收藏数量
     */
    long countByDiscordAccountId(Long discordAccountId);
}
