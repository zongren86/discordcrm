package com.discordadmin.repository;

import com.discordadmin.entity.ApkVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ApkVersionRepository extends JpaRepository<ApkVersion, Long> {
    
    Optional<ApkVersion> findByIsActiveTrue();
    
    Optional<ApkVersion> findByVersion(String version);
    
    boolean existsByVersion(String version);
}
