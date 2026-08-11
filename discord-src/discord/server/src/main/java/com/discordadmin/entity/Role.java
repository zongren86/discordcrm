package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "roles")
@Getter
@Setter
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 角色身份：PLATFORM=平台级，MERCHANT=商户级 */
    @Column(name = "role_type", length = 20)
    @Enumerated(EnumType.STRING)
    private RoleType roleType = RoleType.MERCHANT;

    /** 角色所属商户ID（owner），null 表示平台级角色 */
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "description", length = 255)
    private String description;

    /** 预置系统角色不可删除 */
    @Column(name = "builtin")
    private Boolean builtin = false;

    @Column(name = "enabled")
    private Boolean enabled = true;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    /** 适用商户ID集合 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_merchant_ids", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "merchant_id")
    private Set<Long> merchantIds = new HashSet<>();

    /** 角色功能权限（多对多） */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "role_feature",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "feature_id")
    )
    private Set<SysFeature> features = new HashSet<>();

    public enum RoleType {
        PLATFORM, MERCHANT
    }
}
