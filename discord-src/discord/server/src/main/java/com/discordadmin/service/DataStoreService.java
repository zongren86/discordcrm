package com.discordadmin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.discordadmin.model.AutoAddConfig;
import com.discordadmin.model.DiscordAccount;
import com.discordadmin.model.FriendConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DataStoreService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String dataDir = System.getProperty("user.home") + "/.discord-admin/data";

    private final List<DiscordAccount> accounts = new CopyOnWriteArrayList<>();
    private final List<FriendConfig> friends = new CopyOnWriteArrayList<>();
    private final AutoAddConfig config = new AutoAddConfig();

    @PostConstruct
    public void init() {
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(dataDir));
        } catch (Exception ignored) {}
        load();
    }

    private File file(String name) {
        return java.nio.file.Paths.get(dataDir, name).toFile();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        try {
            File af = file("accounts.json");
            if (af.exists()) {
                List<DiscordAccount> list = mapper.readValue(af, new TypeReference<List<DiscordAccount>>() {});
                accounts.clear();
                accounts.addAll(list);
            }
            File ff = file("friends.json");
            if (ff.exists()) {
                List<FriendConfig> list = mapper.readValue(ff, new TypeReference<List<FriendConfig>>() {});
                friends.clear();
                friends.addAll(list);
            }
            File cf = file("autoconfig.json");
            if (cf.exists()) {
                AutoAddConfig c = mapper.readValue(cf, AutoAddConfig.class);
                config.setIntervalSeconds(c.getIntervalSeconds());
                config.setDelayMinSeconds(c.getDelayMinSeconds());
                config.setDelayMaxSeconds(c.getDelayMaxSeconds());
                config.setAutoCrawlDiscordAccount(c.isAutoCrawlDiscordAccount());
                config.setCrawlIntervalSeconds(c.getCrawlIntervalSeconds());
                config.setAutoLoginDiscord(c.isAutoLoginDiscord());
                config.setMaxConcurrentEmulators(c.getMaxConcurrentEmulators());
                config.setEmulatorStartIntervalSec(c.getEmulatorStartIntervalSec());
                config.setTestModeEnabled(c.isTestModeEnabled());
                // 新字段
                config.setAddStartTime(c.getAddStartTime());
                config.setAddEndTime(c.getAddEndTime());
                config.setDailyLimit(c.getDailyLimit());
                config.setEstimatedSingleDurationMin(c.getEstimatedSingleDurationMin());
            }
        } catch (Exception e) {
            // 加载失败不影响启动
        }
    }

    private void saveAccounts() {
        try { mapper.writeValue(file("accounts.json"), accounts); } catch (Exception ignored) {}
    }

    private void saveFriends() {
        try { mapper.writeValue(file("friends.json"), friends); } catch (Exception ignored) {}
    }

    private void saveConfig() {
        try { mapper.writeValue(file("autoconfig.json"), config); } catch (Exception ignored) {}
    }

    public List<DiscordAccount> getAccounts() {
        return new ArrayList<>(accounts);
    }

    public void setAccounts(List<DiscordAccount> list) {
        accounts.clear();
        if (list != null) accounts.addAll(list);
        saveAccounts();
    }

    public void addAccount(DiscordAccount a) {
        accounts.add(a);
        saveAccounts();
    }

    public DiscordAccount getAccountByIndex(int index) {
        return (index >= 0 && index < accounts.size()) ? accounts.get(index) : null;
    }

    public synchronized DiscordAccount takeAccount(int index) {
        DiscordAccount bound = getAccountByEmulator(index);
        if (bound != null) {
            bound.setLoginStatus("ASSIGNED");
            bound.setLoginError(null);
            saveAccounts();
            return bound;
        }
        for (DiscordAccount a : accounts) {
            if (a.getBoundTo() < 0) {
                a.setBoundTo(index);
                a.setLoginStatus("ASSIGNED");
                a.setLoginError(null);
                saveAccounts();
                return a;
            }
        }
        return null;
    }

    public DiscordAccount getAccountByEmulator(int index) {
        for (DiscordAccount a : accounts) {
            if (a.getBoundTo() == index) return a;
        }
        return null;
    }

    public synchronized void markAccountLogin(int index, boolean success, String error) {
        for (DiscordAccount a : accounts) {
            if (a.getBoundTo() == index) {
                a.setLoginStatus(success ? "LOGIN_SUCCESS" : "LOGIN_FAILED");
                a.setLoginError(success ? null : error);
                saveAccounts();
                return;
            }
        }
    }

    public List<FriendConfig> getFriends() {
        return new ArrayList<>(friends);
    }

    public List<FriendConfig> getFriendsByStatus(String status) {
        return friends.stream()
                .filter(f -> status.equals(f.getStatus()))
                .collect(java.util.stream.Collectors.toList());
    }

    public Map<String, Object> getFriendsStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", friends.size());
        stats.put("pending", friends.stream().filter(f -> FriendConfig.PENDING.equals(f.getStatus())).count());
        stats.put("assigned", friends.stream().filter(f -> FriendConfig.PROCESSING.equals(f.getStatus())).count());
        stats.put("success", friends.stream().filter(f -> FriendConfig.SUCCESS.equals(f.getStatus())).count());
        stats.put("failed", friends.stream().filter(f -> FriendConfig.FAILED.equals(f.getStatus())).count());
        return stats;
    }

    public void setFriends(List<FriendConfig> list) {
        Map<String, FriendConfig> existing = new LinkedHashMap<>();
        for (FriendConfig f : friends) existing.put(f.getUsername(), f);
        friends.clear();
        if (list != null) {
            for (FriendConfig f : list) {
                if (f.getUsername() == null || f.getUsername().trim().isEmpty()) continue;
                String u = f.getUsername().trim();
                FriendConfig prev = existing.get(u);
                if (prev != null && !FriendConfig.PENDING.equals(prev.getStatus())) {
                    friends.add(prev);
                } else {
                    FriendConfig nf = new FriendConfig(u);
                    friends.add(nf);
                }
            }
        }
        saveFriends();
    }

    public void addFriend(FriendConfig f) {
        if (f.getUsername() != null && !f.getUsername().trim().isEmpty()) {
            String u = f.getUsername().trim();
            if (friends.stream().noneMatch(x -> x.getUsername().equals(u))) {
                friends.add(new FriendConfig(u));
                saveFriends();
            }
        }
    }

    public synchronized FriendConfig takeOne(int emulatorIndex) {
        for (FriendConfig f : friends) {
            if (FriendConfig.PENDING.equals(f.getStatus())) {
                f.setStatus(FriendConfig.PROCESSING);
                f.setUsedBy(String.valueOf(emulatorIndex));
                f.setUpdatedAt(System.currentTimeMillis());
                f.setLastResult("处理中");
                saveFriends();
                return f;
            }
        }
        return null;
    }

    public synchronized void markSuccess(String username) {
        for (FriendConfig f : friends) {
            if (f.getUsername().equals(username)) {
                f.setStatus(FriendConfig.SUCCESS);
                f.setLastResult("添加成功");
                f.setUpdatedAt(System.currentTimeMillis());
                saveFriends();
                return;
            }
        }
    }

    public synchronized void markFailed(String username, String reason) {
        for (FriendConfig f : friends) {
            if (f.getUsername().equals(username)) {
                f.setStatus(FriendConfig.FAILED);
                f.setLastResult(reason);
                f.setUpdatedAt(System.currentTimeMillis());
                saveFriends();
                return;
            }
        }
    }

    public String exportFriendsCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("username,status,usedBy,lastResult,updatedAt\n");
        for (FriendConfig f : friends) {
            sb.append(csvCell(f.getUsername())).append(',')
              .append(csvCell(f.getStatus())).append(',')
              .append(csvCell(f.getUsedBy())).append(',')
              .append(csvCell(f.getLastResult())).append(',')
              .append(csvCell(f.getUpdatedAt() == null ? "" : String.valueOf(f.getUpdatedAt())))
              .append('\n');
        }
        return sb.toString();
    }

    private String csvCell(String v) {
        if (v == null) return "";
        String s = v.replace("\"", "\"\"");
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s + "\"";
        }
        return s;
    }

    public AutoAddConfig getConfig() {
        return config;
    }

    public void updateConfig(int interval, int delayMin, int delayMax) {
        config.setIntervalSeconds(interval);
        config.setDelayMinSeconds(delayMin);
        config.setDelayMaxSeconds(delayMax);
        saveConfig();
    }

    public void updateCrawlConfig(boolean autoCrawl, int crawlInterval) {
        config.setAutoCrawlDiscordAccount(autoCrawl);
        config.setCrawlIntervalSeconds(crawlInterval);
        saveConfig();
    }

    public void updateAutoLoginConfig(boolean autoLogin) {
        config.setAutoLoginDiscord(autoLogin);
        saveConfig();
    }

    public synchronized void updateFullConfig(Map<String, Object> body) {
        if (body.containsKey("intervalSeconds"))
            config.setIntervalSeconds(toInt(body.get("intervalSeconds"), config.getIntervalSeconds()));
        if (body.containsKey("delayMinSeconds"))
            config.setDelayMinSeconds(toInt(body.get("delayMinSeconds"), config.getDelayMinSeconds()));
        if (body.containsKey("delayMaxSeconds"))
            config.setDelayMaxSeconds(toInt(body.get("delayMaxSeconds"), config.getDelayMaxSeconds()));
        if (body.containsKey("autoCrawlDiscordAccount"))
            config.setAutoCrawlDiscordAccount(Boolean.parseBoolean(String.valueOf(body.get("autoCrawlDiscordAccount"))));
        if (body.containsKey("crawlIntervalSeconds"))
            config.setCrawlIntervalSeconds(toInt(body.get("crawlIntervalSeconds"), config.getCrawlIntervalSeconds()));
        if (body.containsKey("autoLoginDiscord"))
            config.setAutoLoginDiscord(Boolean.parseBoolean(String.valueOf(body.get("autoLoginDiscord"))));
        if (body.containsKey("maxConcurrentEmulators"))
            config.setMaxConcurrentEmulators(toInt(body.get("maxConcurrentEmulators"), config.getMaxConcurrentEmulators()));
        if (body.containsKey("emulatorStartIntervalSec"))
            config.setEmulatorStartIntervalSec(toInt(body.get("emulatorStartIntervalSec"), config.getEmulatorStartIntervalSec()));
        if (body.containsKey("testModeEnabled"))
            config.setTestModeEnabled(Boolean.parseBoolean(String.valueOf(body.get("testModeEnabled"))));
        // 新字段
        if (body.containsKey("addStartTime"))
            config.setAddStartTime(String.valueOf(body.get("addStartTime")));
        if (body.containsKey("addEndTime"))
            config.setAddEndTime(String.valueOf(body.get("addEndTime")));
        if (body.containsKey("dailyLimit"))
            config.setDailyLimit(toInt(body.get("dailyLimit"), config.getDailyLimit()));
        if (body.containsKey("estimatedSingleDurationMin"))
            config.setEstimatedSingleDurationMin(toInt(body.get("estimatedSingleDurationMin"), config.getEstimatedSingleDurationMin()));
        // 自动计算间隔时间
        int calculatedInterval = config.calculateIntervalMinutes();
        if (calculatedInterval > 0) {
            config.setIntervalSeconds(calculatedInterval * 60);
        }
        saveConfig();
    }

    private int toInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }
}
