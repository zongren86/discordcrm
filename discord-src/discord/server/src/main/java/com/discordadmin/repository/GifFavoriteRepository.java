package com.discordadmin.repository;

import com.discordadmin.entity.GifFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GifFavoriteRepository extends JpaRepository<GifFavorite, Long> {

    /** 按 userId 查询（跨所有 Discord 账号共享） */
    List<GifFavorite> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 按 userId + type 查询 */
    List<GifFavorite> findByUserIdAndTypeOrderByCreatedAtDesc(Long userId, String type);

    /** 按 merchantId 查询 */
    List<GifFavorite> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    /** 按 userId + URL hash 查重 */
    Optional<GifFavorite> findByUserIdAndGifUrlHash(Long userId, String gifUrlHash);

    /** 按账号查询（旧兼容） */
    List<GifFavorite> findByDiscordAccountIdOrderByCreatedAtDesc(Long discordAccountId);

    /** 按账号 + URL hash 查重（旧兼容） */
    Optional<GifFavorite> findByDiscordAccountIdAndGifUrlHash(Long discordAccountId, String gifUrlHash);

    void deleteByIdAndUserId(Long id, Long userId);

    void deleteByDiscordAccountId(Long discordAccountId);

    /** 全部收藏按时间倒序（平台管理员用） */
    List<GifFavorite> findAllByOrderByCreatedAtDesc();

    /** 同时按 userId OR merchantId 查询（跨账号 + 兜底历史无 userId 的收藏） */
    @Query("SELECT g FROM GifFavorite g WHERE g.userId = :userId " +
            "OR (:merchantId IS NOT NULL AND g.merchantId = :merchantId) " +
            "ORDER BY g.createdAt DESC")
    List<GifFavorite> findByUserIdOrMerchantId(@Param("userId") Long userId,
                                               @Param("merchantId") Long merchantId);

    /** 推断用户所属 merchantId（从历史收藏或账号关联） */
    @Query(value = "SELECT merchant_id FROM ( " +
            "  SELECT merchant_id FROM gif_favorites WHERE user_id = :userId AND merchant_id IS NOT NULL LIMIT 1 " +
            "  UNION " +
            "  SELECT da.merchant_id FROM user_discord_accounts uda " +
            "  JOIN discord_accounts da ON uda.discord_account_id = da.id " +
            "  WHERE uda.user_id = :userId AND da.merchant_id IS NOT NULL LIMIT 1 " +
            ") t LIMIT 1", nativeQuery = true)
    Optional<Long> findMerchantIdByUserId(@Param("userId") Long userId);

    long countByUserId(Long userId);
}
