package com.discordadmin.repository;

import com.discordadmin.entity.EmuInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmuInstanceRepository extends JpaRepository<EmuInstance, Long> {

    List<EmuInstance> findByMerchantId(Long merchantId);

    List<EmuInstance> findByMerchantIdAndUserId(Long merchantId, String userId);

    Optional<EmuInstance> findByMerchantIdAndUserIdAndInstanceIndex(Long merchantId, String userId, Integer instanceIndex);

    List<EmuInstance> findByStatus(EmuInstance.EmuStatus status);

    long countByMerchantId(Long merchantId);

    void deleteByMerchantId(Long merchantId);

    void deleteByMerchantIdAndInstanceIndex(Long merchantId, Integer instanceIndex);

    /**
     * 按实例索引查找（定时检测场景使用，物理模拟器索引唯一）
     */
    EmuInstance findFirstByInstanceIndex(Integer instanceIndex);
}
