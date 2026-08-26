package com.discordadmin.discord.member;

/**
 * Gateway 取数过程中的异常，携带可选的 HTTP 风格状态码。
 */
public class GatewayException extends Exception {

    private final int status;

    public GatewayException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
