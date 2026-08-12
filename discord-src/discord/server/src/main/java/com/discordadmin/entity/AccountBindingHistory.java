package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "account_binding_history")
@Getter
@Setter
public class AccountBindingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 账号编号ID */
    @Column(name = "account_number_id", nullable = false)
    private Long accountNumberId;

    /** 修改前账号 */
    @Column(name = "old_account", length = 256)
    private String oldAccount;

    /** 修改后账号 */
    @Column(name = "new_account", length = 256)
    private String newAccount;

    /** 修改原因 */
    @Column(name = "change_reason", length = 512)
    private String changeReason;

    /** 修改人ID */
    @Column(name = "operator_id")
    private Long operatorId;

    /** 修改人用户名 */
    @Column(name = "operator_name", length = 64)
    private String operatorName;

    @Column(name = "changed_at")
    private Instant changedAt = Instant.now();
}
