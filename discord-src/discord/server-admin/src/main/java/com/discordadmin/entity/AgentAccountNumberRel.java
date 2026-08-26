package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "agent_account_number_rels")
@Getter
@Setter
public class AgentAccountNumberRel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID（关联user表） */
    @Column(name = "user_id", nullable = false)
    private Long agentId;

    /** 账号编号ID */
    @Column(name = "account_number_id", nullable = false)
    private Long accountNumberId;

    /** 关联时间 */
    @Column(name = "linked_at")
    private Instant linkedAt = Instant.now();

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
