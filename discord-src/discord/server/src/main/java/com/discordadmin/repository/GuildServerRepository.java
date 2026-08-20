package com.discordadmin.repository;

import com.discordadmin.entity.GuildServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GuildServerRepository extends JpaRepository<GuildServer, Long> {
    List<GuildServer> findByMerchantId(Long merchantId);
    List<GuildServer> findByDiscordAccountId(Long discordAccountId);
    List<GuildServer> findByMerchantIdAndDiscordAccountId(Long merchantId, Long discordAccountId);
    Optional<GuildServer> findByDiscordAccountIdAndGuildId(Long discordAccountId, String guildId);
    
    // 检查同一账号+服务器是否存在（排除指定ID）
    Optional<GuildServer> findByDiscordAccountIdAndGuildIdAndIdNot(
        Long discordAccountId, String guildId, Long excludeId);
        
    // 检查同一账号+服务器是否存在
    boolean existsByDiscordAccountIdAndGuildId(Long discordAccountId, String guildId);
    
    // 删除指定账号关联的所有服务器
    void deleteByDiscordAccountId(Long discordAccountId);

    // 按账号ID列表查询（用于权限过滤）
    List<GuildServer> findByDiscordAccountIdIn(List<Long> discordAccountIds);
    List<GuildServer> findByMerchantIdAndDiscordAccountIdIn(Long merchantId, List<Long> discordAccountIds);
}
