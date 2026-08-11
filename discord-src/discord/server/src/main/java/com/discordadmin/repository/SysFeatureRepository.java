package com.discordadmin.repository;

import com.discordadmin.entity.SysFeature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface SysFeatureRepository extends JpaRepository<SysFeature, Long> {
    List<SysFeature> findAllByOrderBySortOrderAsc();
    boolean existsByCode(String code);
    Optional<SysFeature> findByCode(String code);
    List<SysFeature> findByCodeIn(Set<String> codes);
}
