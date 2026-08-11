package com.discordadmin.repository;

import com.discordadmin.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByAgentIdOrderByCreatedAtDesc(Long agentId);

    Page<Notification> findByAgentIdOrderByCreatedAtDesc(Long agentId, Pageable pageable);

    long countByAgentIdAndIsReadFalse(Long agentId);

    List<Notification> findByAgentIdAndIsReadFalseOrderByCreatedAtDesc(Long agentId);
}
