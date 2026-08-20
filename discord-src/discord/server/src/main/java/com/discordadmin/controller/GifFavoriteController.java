package com.discordadmin.controller;

import com.discordadmin.entity.GifFavorite;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.GifFavoriteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GIF 收藏 Controller
 */
@RestController
@RequestMapping("/api/gif-favorites")
public class GifFavoriteController {

    private static final Logger log = LoggerFactory.getLogger(GifFavoriteController.class);

    private final GifFavoriteService gifFavoriteService;

    public GifFavoriteController(GifFavoriteService gifFavoriteService) {
        this.gifFavoriteService = gifFavoriteService;
    }

    /**
     * 获取当前账号的 GIF 收藏列表
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listFavorites(
            @RequestParam(value = "accountId", required = false) Long accountId) {
        // 如果没有指定账号，使用当前会话的账号
        // 这里可以根据前端传参获取，或从当前上下文获取
        Long targetAccountId = accountId;
        
        // 如果是平台管理员，可以查看任意账号的收藏
        if ("PLATFORM_ADMIN".equals(SecurityUtils.currentRole()) && targetAccountId == null) {
            return ResponseEntity.ok(List.of());
        }
        
        if (targetAccountId == null) {
            return ResponseEntity.badRequest().body(List.of());
        }
        
        List<GifFavorite> favorites = gifFavoriteService.getFavoritesByAccount(targetAccountId);
        List<Map<String, Object>> result = favorites.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(result);
    }

    /**
     * 添加 GIF 收藏
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addFavorite(@RequestBody Map<String, String> body) {
        String gifUrl = body.get("gifUrl");
        Long accountId = Long.parseLong(body.get("accountId"));
        String title = body.get("title");
        
        if (gifUrl == null || gifUrl.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "GIF URL 不能为空"));
        }
        
        GifFavorite favorite = gifFavoriteService.addFavorite(accountId, gifUrl, title);
        return ResponseEntity.ok(toDto(favorite));
    }

    /**
     * 删除 GIF 收藏
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> removeFavorite(
            @PathVariable Long id,
            @RequestParam Long accountId) {
        gifFavoriteService.removeFavorite(id, accountId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * 检查 GIF 是否已收藏
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkFavorited(
            @RequestParam Long accountId,
            @RequestParam String gifUrl) {
        boolean isFavorited = gifFavoriteService.isFavorited(accountId, gifUrl);
        Map<String, Object> result = new HashMap<>();
        result.put("favorited", isFavorited);
        return ResponseEntity.ok(result);
    }

    /**
     * 规范化 GIF URL（确保可直接发送）
     */
    @GetMapping("/normalize-url")
    public ResponseEntity<Map<String, String>> normalizeUrl(@RequestParam String url) {
        String normalized = gifFavoriteService.normalizeGifUrl(url);
        return ResponseEntity.ok(Map.of("originalUrl", url, "normalizedUrl", normalized));
    }

    /**
     * 转换为 DTO
     */
    private Map<String, Object> toDto(GifFavorite favorite) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", favorite.getId());
        dto.put("gifUrl", favorite.getGifUrl());
        dto.put("title", favorite.getTitle());
        dto.put("sourceDomain", favorite.getSourceDomain());
        dto.put("createdAt", favorite.getCreatedAt());
        dto.put("accountId", favorite.getDiscordAccount().getId());
        return dto;
    }
}
