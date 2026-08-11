package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_merchant", columnList = "merchant_id"),
        @Index(name = "idx_audit_time", columnList = "created_at"),
        @Index(name = "idx_audit_action", columnList = "action")
})
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id")
    private Long merchantId;

    /** 操作人用户名 */
    @Column(name = "operator", length = 64)
    private String operator;

    /** 角色 */
    @Column(name = "operator_role", length = 32)
    private String operatorRole;

    /** 模块：auth/account/conversation/customer/user/merchant/role/system */
    @Column(name = "module", length = 32)
    private String module;

    /** 动作：CREATE/UPDATE/DELETE/LOGIN/LOGOUT/EXPORT */
    @Column(name = "action", length = 32)
    private String action;

    /** 资源类型 */
    @Column(name = "resource_type", length = 64)
    private String resourceType;

    /** 资源ID */
    @Column(name = "resource_id", length = 64)
    private String resourceId;

    /** 详情 */
    @Column(name = "detail", length = 2000)
    private String detail;

    /** IP地址 */
    @Column(name = "ip", length = 64)
    private String ip;

    /** 结果：SUCCESS/FAIL */
    @Column(name = "result", length = 16)
    private String result = "SUCCESS";

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
