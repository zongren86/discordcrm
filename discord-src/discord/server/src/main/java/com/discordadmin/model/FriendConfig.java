package com.discordadmin.model;

public class FriendConfig {
    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private String username;
    private String status = PENDING;
    private String usedBy;
    private String lastResult;
    private Long updatedAt;

    public FriendConfig() {}

    public FriendConfig(String username) {
        this.username = username;
        this.status = PENDING;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getUsedBy() { return usedBy; }
    public void setUsedBy(String usedBy) { this.usedBy = usedBy; }
    public String getLastResult() { return lastResult; }
    public void setLastResult(String lastResult) { this.lastResult = lastResult; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FriendConfig)) return false;
        return username != null && username.equals(((FriendConfig) o).username);
    }

    @Override
    public int hashCode() {
        return username != null ? username.hashCode() : 0;
    }
}
