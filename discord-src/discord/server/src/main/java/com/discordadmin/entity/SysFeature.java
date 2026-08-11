package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sys_features")
@Getter
@Setter
public class SysFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "parent_id")
    private Long parentId;

    /** MENU_1 / MENU_2 / MENU_3 / BUTTON */
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    /** 按钮子类型（仅 type=BUTTON 时有效）：TAB / TOOLBAR / ROW_ACTION */
    @Column(name = "btn_type", length = 16)
    private String btnType;

    @Column(name = "icon", length = 64)
    private String icon;

    @Column(name = "route_path", length = 255)
    private String routePath;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
