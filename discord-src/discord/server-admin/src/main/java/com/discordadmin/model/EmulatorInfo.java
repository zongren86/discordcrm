package com.discordadmin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmulatorInfo {
    private int index;
    private String name;
    private String status;         // RUNNING, STOPPED, CREATING, ERROR, DAMAGED
    private int adbPort;
    private int androidPort;
    private int frontendPort;
    private boolean discordInstalled;
    private boolean discordLoggedIn;
    private boolean damaged;        // 是否损坏（启动健康检查未通过）
    private String damageReason;    // 损坏原因
    private String lastError;
    private String screenshot;     // base64 encoded screenshot
    private int cpuCount;
    private int memoryMB;
    private String resolution;

    // ---- 自动加好友相关 ----
    private String discordAccount;     // 当前登录的 discord 账号（邮箱，预设/分配）
    private String discordActualUser;  // 实时抓取的 Discord 用户名（显示用）
    private boolean discordLoginFailed; // 自动登录是否失败
    private String discordLoginError;   // 自动登录失败原因
    private int addedCount;            // 已发送好友请求数
    private long nextAddAt;           // 下次添加的时间戳(ms)，0 表示未排程
    private boolean autoRunning;      // 自动加好友是否运行中
    private String autoLastResult;    // 最近一次自动操作结果
}
