package com.discordadmin.repository;

import com.discordadmin.entity.EmuAccountBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmuAccountBindingRepository extends JpaRepository<EmuAccountBinding, Long> {

    List<EmuAccountBinding> findByMerchantId(Long merchantId);

    List<EmuAccountBinding> findByUserId(Long userId);

    List<EmuAccountBinding> findByMerchantIdAndStatus(Long merchantId, EmuAccountBinding.BindingStatus status);

    Optional<EmuAccountBinding> findByDiscordAccountIdAndStatus(Long discordAccountId, EmuAccountBinding.BindingStatus status);

    boolean existsByDiscordAccountIdAndStatus(Long discordAccountId, EmuAccountBinding.BindingStatus status);

    long countByMerchantId(Long merchantId);

    long countByMerchantIdAndStatus(Long merchantId, EmuAccountBinding.BindingStatus status);
}
