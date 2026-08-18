package com.discordadmin.controller;

import com.discordadmin.model.AutoAddConfig;
import com.discordadmin.model.DiscordAccount;
import com.discordadmin.model.FriendConfig;
import com.discordadmin.service.DataStoreService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/data")
public class DataController {

    private final DataStoreService dataStore;

    public DataController(DataStoreService dataStore) {
        this.dataStore = dataStore;
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<DiscordAccount>> getAccounts() {
        return ResponseEntity.ok(dataStore.getAccounts());
    }

    @PostMapping("/accounts")
    public ResponseEntity<Map<String, String>> setAccounts(@RequestBody List<DiscordAccount> accounts) {
        dataStore.setAccounts(accounts);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/accounts/add")
    public ResponseEntity<Map<String, String>> addAccount(@RequestBody DiscordAccount account) {
        dataStore.addAccount(account);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/friends")
    public ResponseEntity<List<FriendConfig>> getFriends(@RequestParam(required = false) String status) {
        if (status != null && !status.isEmpty()) {
            return ResponseEntity.ok(dataStore.getFriendsByStatus(status));
        }
        return ResponseEntity.ok(dataStore.getFriends());
    }

    @GetMapping("/friends/stats")
    public ResponseEntity<Map<String, Object>> getFriendsStats() {
        return ResponseEntity.ok(dataStore.getFriendsStats());
    }

    @PostMapping("/friends")
    public ResponseEntity<Map<String, String>> setFriends(@RequestBody List<FriendConfig> friends) {
        dataStore.setFriends(friends);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/friends/add")
    public ResponseEntity<Map<String, String>> addFriend(@RequestBody FriendConfig friend) {
        dataStore.addFriend(friend);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/friends/export")
    public ResponseEntity<byte[]> exportFriendsCsv() {
        String csv = dataStore.exportFriendsCsv();
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=utf-8"));
        headers.setContentDispositionFormData("attachment", "friends_pool.csv");
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    @GetMapping("/autoconfig")
    public ResponseEntity<AutoAddConfig> getConfig() {
        return ResponseEntity.ok(dataStore.getConfig());
    }

    @PostMapping("/autoconfig")
    public ResponseEntity<Map<String, String>> updateConfig(@RequestBody Map<String, Object> body) {
        int interval = toInt(body.get("intervalSeconds"), 900);
        int delayMin = toInt(body.get("delayMinSeconds"), 60);
        int delayMax = toInt(body.get("delayMaxSeconds"), 800);
        dataStore.updateConfig(interval, delayMin, delayMax);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/autoconfig/crawl")
    public ResponseEntity<Map<String, String>> updateCrawlConfig(@RequestBody Map<String, Object> body) {
        boolean autoCrawl = Boolean.parseBoolean(String.valueOf(body.getOrDefault("autoCrawl", false)));
        int crawlInterval = toInt(body.get("crawlIntervalSeconds"), 300);
        dataStore.updateCrawlConfig(autoCrawl, crawlInterval);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/autoconfig/autologin")
    public ResponseEntity<Map<String, String>> updateAutoLoginConfig(@RequestBody Map<String, Object> body) {
        boolean autoLogin = Boolean.parseBoolean(String.valueOf(body.getOrDefault("autoLogin", false)));
        dataStore.updateAutoLoginConfig(autoLogin);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private int toInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }
}
