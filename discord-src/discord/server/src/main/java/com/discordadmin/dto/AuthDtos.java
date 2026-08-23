package com.discordadmin.dto;

import java.util.List;

public class AuthDtos {

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, Long agentId, String username, String displayName,
                                 Integer accountType, Long merchantId, String merchantName,
                                 List<String> permissions) {
    }
}
