package com.discordadmin.repository;

import com.discordadmin.entity.AutoAddConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AutoAddConfigRepository extends JpaRepository<AutoAddConfigEntity, Long> {
    Optional<AutoAddConfigEntity> findByMerchantIdAndUserId(Long merchantId, String userId);
}
