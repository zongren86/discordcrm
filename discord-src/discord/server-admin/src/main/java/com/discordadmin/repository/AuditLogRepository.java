package com.discordadmin.repository;

import com.discordadmin.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByMerchantIdOrderByCreatedAtDesc(Long merchantId, Pageable pageable);

    @Query("SELECT a FROM AuditLog a WHERE " +
            "(a.merchantId = :merchantId OR :merchantId IS NULL) " +
            "AND (:module IS NULL OR a.module = :module) " +
            "AND (:action IS NULL OR a.action = :action) " +
            "AND (:operator IS NULL OR a.operator LIKE CONCAT('%',:operator,'%')) " +
            "AND a.createdAt >= :since AND a.createdAt <= :until " +
            "ORDER BY a.createdAt DESC")
    List<AuditLog> search(@Param("merchantId") Long merchantId,
                          @Param("module") String module,
                          @Param("action") String action,
                          @Param("operator") String operator,
                          @Param("since") Instant since,
                          @Param("until") Instant until);
}
