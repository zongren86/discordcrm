package com.discordadmin.dto;

import java.time.Instant;

public class UserStatsDtos {

    public record ActiveCustomerDto(
            String discordUserId,
            String username,
            String globalName,
            String avatarUrl,
            Long messageCount,
            Instant lastMessageAt
    ) {
    }
}
