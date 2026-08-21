package com.discordadmin.model;

public class AutoAddConfig {
    private int intervalSeconds = 900;
    private int delayMinSeconds = 60;
    private int delayMaxSeconds = 800;
    private boolean autoCrawlDiscordAccount = false;
    private int crawlIntervalSeconds = 300;
    private boolean autoLoginDiscord = false;
    private int maxConcurrentEmulators = 5;
    private int emulatorStartIntervalSec = 5;
    private boolean testModeEnabled = false;

    public AutoAddConfig() {}

    public int getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(int v) { this.intervalSeconds = Math.max(0, v); }
    public int getDelayMinSeconds() { return delayMinSeconds; }
    public void setDelayMinSeconds(int v) { this.delayMinSeconds = Math.max(0, v); }
    public int getDelayMaxSeconds() { return delayMaxSeconds; }
    public void setDelayMaxSeconds(int v) { this.delayMaxSeconds = Math.max(0, v); }
    public boolean isAutoCrawlDiscordAccount() { return autoCrawlDiscordAccount; }
    public void setAutoCrawlDiscordAccount(boolean v) { this.autoCrawlDiscordAccount = v; }
    public int getCrawlIntervalSeconds() { return crawlIntervalSeconds; }
    public void setCrawlIntervalSeconds(int v) { this.crawlIntervalSeconds = Math.max(30, v); }
    public boolean isAutoLoginDiscord() { return autoLoginDiscord; }
    public void setAutoLoginDiscord(boolean v) { this.autoLoginDiscord = v; }
    public int getMaxConcurrentEmulators() { return maxConcurrentEmulators; }
    public void setMaxConcurrentEmulators(int v) { this.maxConcurrentEmulators = Math.max(1, Math.min(200, v)); }
    public int getEmulatorStartIntervalSec() { return emulatorStartIntervalSec; }
    public void setEmulatorStartIntervalSec(int v) { this.emulatorStartIntervalSec = Math.max(1, Math.min(3600, v)); }
    public boolean isTestModeEnabled() { return testModeEnabled; }
    public void setTestModeEnabled(boolean v) { this.testModeEnabled = v; }
}
