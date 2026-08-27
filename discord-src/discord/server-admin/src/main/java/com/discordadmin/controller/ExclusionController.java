package com.discordadmin.controller;

import com.discordadmin.entity.FriendExclusionConfig;
import com.discordadmin.entity.FriendExclusionUser;
import com.discordadmin.security.JwtAuthFilter;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.ExclusionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 好友排除配置 REST API
 * 路径前缀: /api/exclusion
 */
@RestController
@RequestMapping("/api/exclusion")
@RequiredArgsConstructor
@Slf4j
public class ExclusionController {

    private final ExclusionService exclusionService;

    private Long currentMerchantId() {
        JwtAuthFilter.AuthenticatedAgent agent = SecurityUtils.currentAgent();
        if (agent == null || agent.merchantId() == null) {
            throw new IllegalArgumentException("未登录或无商户");
        }
        return agent.merchantId();
    }

    private Long currentUserId() {
        JwtAuthFilter.AuthenticatedAgent agent = SecurityUtils.currentAgent();
        return agent != null && agent.userId() != null ? agent.userId() : 1L;
    }

    // ======== 配置 CRUD ========

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> data = exclusionService.getConfig(currentMerchantId(), currentUserId());
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("data", data);
        return ResponseEntity.ok(r);
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> body) {
        Boolean excludeAllFriends = (Boolean) body.getOrDefault("excludeAllFriends", false);
        Boolean useCustomList = (Boolean) body.getOrDefault("useCustomList", false);
        FriendExclusionConfig saved = exclusionService.saveConfig(
                currentMerchantId(), currentUserId(), excludeAllFriends, useCustomList);
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("message", "配置已保存");
        r.put("id", saved.getId());
        return ResponseEntity.ok(r);
    }

    // ======== 指定清单 ========

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "50") int size) {
        Page<FriendExclusionUser> p = exclusionService.getCustomPage(
                currentMerchantId(), currentUserId(), PageRequest.of(page, size));
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("content", p.getContent());
        r.put("totalElements", p.getTotalElements());
        r.put("totalPages", p.getTotalPages());
        r.put("number", p.getNumber());
        r.put("size", p.getSize());
        return ResponseEntity.ok(r);
    }

    @PostMapping("/users/upload")
    public ResponseEntity<Map<String, Object>> uploadExcel(@RequestParam("file") MultipartFile file,
                                                            @RequestParam(value = "mode", defaultValue = "append") String mode) throws Exception {
        int count;
        if ("replace".equalsIgnoreCase(mode)) {
            count = exclusionService.replaceAllFromExcel(currentMerchantId(), currentUserId(), file);
        } else {
            count = exclusionService.uploadFromExcel(currentMerchantId(), currentUserId(), file);
        }
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("message", (mode.equalsIgnoreCase("replace") ? "已替换" : "已追加") + count + " 个用户名");
        r.put("count", count);
        return ResponseEntity.ok(r);
    }

    @PostMapping("/users/batch")
    public ResponseEntity<Map<String, Object>> batchAdd(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) body.getOrDefault("usernames", Collections.emptyList());
        int added = exclusionService.addUsernames(currentMerchantId(), currentUserId(), names, "batch_api");
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("count", added);
        return ResponseEntity.ok(r);
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> deleteOne(@PathVariable Long id) {
        int ok = exclusionService.deleteById(id);
        Map<String, Object> r = new HashMap<>();
        r.put("success", ok > 0);
        return ResponseEntity.ok(r);
    }

    @DeleteMapping("/users")
    public ResponseEntity<Map<String, Object>> clear() {
        int ok = exclusionService.clearAll(currentMerchantId(), currentUserId());
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("deleted", ok);
        return ResponseEntity.ok(r);
    }

    // ======== Excel 模板 ========

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        byte[] data = exclusionService.generateTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"userlist_template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(data);
    }

    // ======== 手动触发排除应用 ========

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> serverIds = body != null ? (List<Long>) body.get("serverIds") : null;
        int updated = exclusionService.markExcludedAfterServerSync(
                currentMerchantId(), currentUserId(), serverIds);
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("message", "已将 " + updated + " 个服务器成员标记为已排除");
        r.put("updated", updated);
        return ResponseEntity.ok(r);
    }
}
