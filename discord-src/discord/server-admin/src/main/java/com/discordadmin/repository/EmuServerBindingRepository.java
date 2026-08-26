package com.discordadmin.repository;

import com.discordadmin.entity.EmuServerBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmuServerBindingRepository extends JpaRepository<EmuServerBinding, Long> {

    List<EmuServerBinding> findByMerchantId(Long merchantId);

    List<EmuServerBinding> findByUserId(Long userId);

    List<EmuServerBinding> findByServerId(Long serverId);

    List<EmuServerBinding> findByMerchantIdAndStatus(Long merchantId, EmuServerBinding.BindingStatus status);

    Optional<EmuServerBinding> findByServerIdAndStatus(Long serverId, EmuServerBinding.BindingStatus status);

    boolean existsByServerIdAndStatus(Long serverId, EmuServerBinding.BindingStatus status);

    long countByMerchantId(Long merchantId);
}
