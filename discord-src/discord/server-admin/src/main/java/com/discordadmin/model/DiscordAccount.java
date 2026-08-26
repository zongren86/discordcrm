package com.discordadmin.model;

public class DiscordAccount {
    private String email;
    private String password;
    private int boundTo = -1;
    private String loginStatus = "UNASSIGNED";
    private String loginError;

    public DiscordAccount() {}
    public DiscordAccount(String email, String password) {
        this.email = email;
        this.password = password;
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getBoundTo() { return boundTo; }
    public void setBoundTo(int boundTo) { this.boundTo = boundTo; }
    public String getLoginStatus() { return loginStatus; }
    public void setLoginStatus(String loginStatus) { this.loginStatus = loginStatus; }
    public String getLoginError() { return loginError; }
    public void setLoginError(String loginError) { this.loginError = loginError; }
}
