package com.discordadmin.discord.member;

import lombok.Data;

/**
 * 启动一次成员采集的请求体。
 */
@Data
public class MemberFetchRequest {
    /** Discord Bot Token */
    private String token;
    /** 服务器链接或 Guild ID */
    private String link;
    /** 服务器配置 ID（用于关联存储） */
    private Long guildServerId;
    /** 关联的 Discord 账号 ID */
    private Long discordAccountId;
    /** 获取数量上限（默认 2000000） */
    private int maxMembers = 2000000;
    /** 请求间隔（秒，防限流） */
    private double pageDelay = 1.0;
    /** 每次请求数 */
    private int requestCount = 100;
    /** 最大下钻深度（默认 5） */
    private int maxDepth = 5;
    /** 最大请求数（默认 1000，安全上限） */
    private int maxRequests = 1000;
    /** Channel ID */
    private String channelId;
    /** 是否续传（true=从上次断点继续，false=全量重新同步） */
    private boolean resumeSync = true;
}
