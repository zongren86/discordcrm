package com.discordadmin.controller;

import com.discordadmin.service.MumuAutoAddProxyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/discord")
public class DiscordController {

    private final MumuAutoAddProxyService proxyService;

    public DiscordController(MumuAutoAddProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @PostMapping("/{index}/install")
    public ResponseEntity<Map<String, Object>> install(@PathVariable int index) {
        Map<String, Object> res = proxyService.installDiscord(index);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/apk-status")
    public ResponseEntity<Map<String, Object>> getApkStatus() {
        Map<String, Object> res = proxyService.getApkStatus();
        return ResponseEntity.ok(res);
    }

    @PostMapping("/download")
    public ResponseEntity<Map<String, Object>> download() {
        Map<String, Object> res = proxyService.downloadDiscordApk();
        return ResponseEntity.ok(res);
    }

    @PostMapping("/installAll")
    public ResponseEntity<Map<String, Object>> installAll() {
        Map<String, Object> res = proxyService.installDiscordOnAll();
        return ResponseEntity.ok(res);
    }
}
