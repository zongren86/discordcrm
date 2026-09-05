package com.discordadmin.repository;

import com.discordadmin.entity.AccountTokenEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AccountTokenEventRepository extends JpaRepository<AccountTokenEvent, Long> {

    List<AccountTokenEvent> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    Page<AccountTokenEvent> findByAccountId(Long accountId, Pageable pageable);

    List<AccountTokenEvent> findByAccountIdAndEventTypeOrderByCreatedAtDesc(Long accountId, String eventType);

    @Query("SELECT e FROM AccountTokenEvent e WHERE e.accountId = :accountId AND e.createdAt >= :since ORDER BY e.createdAt")
    List<AccountTokenEvent> findSince(@Param("accountId") Long accountId, @Param("since") java.time.Instant since);

    long countByAccountId(Long accountId);

    long countByEventType(String eventType);
}
