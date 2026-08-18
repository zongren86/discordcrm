package com.discordadmin.controller;

import com.discordadmin.service.MumuAutoAddProxyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/autoadd")
public class AutoAddController {

    private final MumuAutoAddProxyService proxyService;

    public AutoAddController(MumuAutoAddProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @PostMapping("/{index}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable int index) {
        Map<String, Object> res = proxyService.startAutoAdd(index);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/{index}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable int index) {
        Map<String, Object> res = proxyService.stopAutoAdd(index);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/startAll")
    public ResponseEntity<Map<String, Object>> startAll() {
        Map<String, Object> res = proxyService.startAllAutoAdd();
        return ResponseEntity.ok(res);
    }

    @PostMapping("/stopAll")
    public ResponseEntity<Map<String, Object>> stopAll() {
        Map<String, Object> res = proxyService.stopAllAutoAdd();
        return ResponseEntity.ok(res);
    }
}
