package com.discordadmin.repository;

import com.discordadmin.entity.FriendExclusionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FriendExclusionConfigRepository extends JpaRepository<FriendExclusionConfig, Long> {
    Optional<FriendExclusionConfig> findByMerchantIdAndUserId(Long merchantId, Long userId);
}
