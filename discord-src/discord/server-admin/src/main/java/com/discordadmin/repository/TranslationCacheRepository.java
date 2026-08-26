package com.discordadmin.repository;

import com.discordadmin.entity.TranslationCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TranslationCacheRepository extends JpaRepository<TranslationCache, Long> {

    /** 根据哈希+目标语言查找缓存 */
    Optional<TranslationCache> findBySourceHashAndTargetLanguage(String sourceHash, String targetLanguage);

    /** 根据哈希+目标语言删除缓存 */
    void deleteBySourceHashAndTargetLanguage(String sourceHash, String targetLanguage);

    /** 清空所有缓存 */
    @Modifying
    @Query("DELETE FROM TranslationCache")
    void clearAll();

    /** 获取缓存总数 */
    long count();
}
