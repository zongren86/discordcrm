package com.discordadmin.discord.member;

import lombok.Data;

/**
 * 归一化后的成员记录，可直接映射成数据库表字段。
 */
@Data
public class MemberRecord {
    private int index;
    private String serverName;
    private String displayName;
    private String username;
    private String globalName;
    private String userId;
    private String nick;
    private String joinedAt;
    private String source;
    private String fetchedAt;
    private String avatarUrl;
    private Boolean isBot;
    private String roles;
    private String discordStatus; // online, idle, dnd, offline
}
