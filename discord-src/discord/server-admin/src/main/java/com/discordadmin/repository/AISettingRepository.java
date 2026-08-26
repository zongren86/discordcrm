package com.discordadmin.repository;

import com.discordadmin.entity.AISetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AISettingRepository extends JpaRepository<AISetting, Long> {

    List<AISetting> findByMerchantIdOrderByFeatureAsc(Long merchantId);

    List<AISetting> findByMerchantIdIsNullOrderByFeatureAsc();

    Optional<AISetting> findByMerchantIdAndFeature(Long merchantId, String feature);

    Optional<AISetting> findByMerchantIdIsNullAndFeature(String feature);
}
