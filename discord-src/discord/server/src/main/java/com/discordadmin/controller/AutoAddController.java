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
}
