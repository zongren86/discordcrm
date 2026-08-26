package com.discordadmin.repository;

import com.discordadmin.entity.MerchantConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantConfigRepository extends JpaRepository<MerchantConfig, Long> {
    Optional<MerchantConfig> findByMerchantId(Long merchantId);
}
