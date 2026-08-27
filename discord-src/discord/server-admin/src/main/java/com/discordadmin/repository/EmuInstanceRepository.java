package com.discordadmin.repository;

import com.discordadmin.entity.EmuInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmuInstanceRepository extends JpaRepository<EmuInstance, Long> {

    List<EmuInstance> findByMerchantId(Long merchantId);

    List<EmuInstance> findByMerchantIdAndUserId(Long merchantId, Long userId);

    Optional<EmuInstance> findByMerchantIdAndUserIdAndInstanceIndex(Long merchantId, Long userId, Integer instanceIndex);

    /**
     * 按商户ID和实例索引查找（用于删除时的回退查询）
     */
    Optional<EmuInstance> findByMerchantIdAndInstanceIndex(Long merchantId, Integer instanceIndex);

    List<EmuInstance> findByStatus(EmuInstance.EmuStatus status);

    long countByMerchantId(Long merchantId);

    void deleteByMerchantId(Long merchantId);

    void deleteByMerchantIdAndInstanceIndex(Long merchantId, Integer instanceIndex);

    /**
     * 按实例索引查找（定时检测场景使用，物理模拟器索引唯一）
     */
    EmuInstance findFirstByInstanceIndex(Integer instanceIndex);

    /**
     * 按用户ID查找所有模拟器实例
     */
    List<EmuInstance> findByUserId(Long userId);

    /**
     * 按 deviceId + instanceIndex 查找（后台线程无 SecurityContext 时使用）
     */
    Optional<EmuInstance> findByDeviceIdAndInstanceIndex(String deviceId, Integer instanceIndex);
}
