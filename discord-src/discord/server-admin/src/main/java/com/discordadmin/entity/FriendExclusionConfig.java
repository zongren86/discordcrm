package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 商户级 好友排除配置
 * 每个 merchant 只有一条记录 (merchant_id + user_id 唯一)
 */
@Entity
@Table(name = "friend_exclusion_config",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_merchant_user", columnNames = {"merchant_id", "user_id"})
       })
@Getter
@Setter
public class FriendExclusionConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 选项一: 排除本商户所有已加好友的用户
     * 即当前账号下已成功添加 (friend_status=2) 的人, 不再进好友号池
     */
    @Column(name = "exclude_all_friends")
    private Boolean excludeAllFriends = false;

    /**
     * 选项二: 使用指定清单 (FriendExclusionUser 表)
     */
    @Column(name = "use_custom_list")
    private Boolean useCustomList = false;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();
}
