package com.discordadmin.model;

public class AutoAddConfig {
    private int intervalSeconds = 900;
    private int delayMinSeconds = 0;
    private int delayMaxSeconds = 5;
    private boolean autoCrawlDiscordAccount = false;
    private int crawlIntervalSeconds = 300;
    private boolean autoLoginDiscord = false;
    private int maxConcurrentEmulators = 5;
    private int emulatorStartIntervalSec = 5;
    private boolean testModeEnabled = true;
    // 新增：加好友时段（格式 HH:mm）
    private String addStartTime = "09:00";
    private String addEndTime = "18:00";
    // 新增：每天可加人数
    private int dailyLimit = 6;
    // 新增：预估单机完成时长（分钟）
    private int estimatedSingleDurationMin = 5;

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
    
    public String getAddStartTime() { return addStartTime; }
    public void setAddStartTime(String v) { this.addStartTime = (v != null && !v.isBlank()) ? v : "09:00"; }
    public String getAddEndTime() { return addEndTime; }
    public void setAddEndTime(String v) { this.addEndTime = (v != null && !v.isBlank()) ? v : "18:00"; }
    public int getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(int v) { this.dailyLimit = Math.max(1, Math.min(10000, v)); }
    public int getEstimatedSingleDurationMin() { return estimatedSingleDurationMin; }
    public void setEstimatedSingleDurationMin(int v) { this.estimatedSingleDurationMin = Math.max(1, Math.min(1440, v)); }
    
    /**
     * 计算时段分钟数
     */
    public int getPeriodMinutes() {
        int startMinutes = parseTimeToMinutes(addStartTime);
        int endMinutes = parseTimeToMinutes(addEndTime);
        int duration = endMinutes - startMinutes;
        return Math.max(0, duration);
    }
    
    /**
     * 根据时段和每天可加人数计算间隔时间（分钟）
     */
    public int calculateIntervalMinutes() {
        int periodMin = getPeriodMinutes();
        if (periodMin <= 0 || dailyLimit <= 0) return intervalSeconds / 60;
        return Math.max(1, periodMin / dailyLimit);
    }
    
    /**
     * 计算预估总时长（分钟）
     * 预估总时长 = 预估单机完成时长 × (模拟器总数 / 每批次模拟器数)
     */
    public int calculateEstimatedTotalDuration(int totalEmulators) {
        if (totalEmulators <= 0 || maxConcurrentEmulators <= 0) {
            return estimatedSingleDurationMin;
        }
        int batches = (int) Math.ceil((double) totalEmulators / maxConcurrentEmulators);
        return estimatedSingleDurationMin * batches;
    }
    
    /**
     * 判断是否在加好友时段内
     */
    public boolean isInAddPeriod() {
        int nowMinutes = getCurrentMinutesOfDay();
        int startMinutes = parseTimeToMinutes(addStartTime);
        int endMinutes = parseTimeToMinutes(addEndTime);
        return nowMinutes >= startMinutes && nowMinutes <= endMinutes;
    }
    
    /**
     * 解析时间字符串为分钟数（支持 HH:mm 和 HH:mm:ss 格式）
     */
    private int parseTimeToMinutes(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return 0;
        try {
            String[] parts = timeStr.split(":");
            if (parts.length == 2) {
                int hours = Integer.parseInt(parts[0].trim());
                int minutes = Integer.parseInt(parts[1].trim());
                return hours * 60 + minutes;
            } else if (parts.length == 3) {
                int hours = Integer.parseInt(parts[0].trim());
                int minutes = Integer.parseInt(parts[1].trim());
                int seconds = Integer.parseInt(parts[2].trim());
                // 向上取整到分钟
                return hours * 60 + minutes + (seconds > 0 ? 1 : 0);
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }
    
    /**
     * 获取当前时间的分钟数
     */
    private int getCurrentMinutesOfDay() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hours = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int minutes = cal.get(java.util.Calendar.MINUTE);
        return hours * 60 + minutes;
    }
}
