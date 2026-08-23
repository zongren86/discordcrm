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
     * 获取当前账号的 GIF/Sticker 收藏列表
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listFavorites(
            @RequestParam(value = "accountId", required = false) Long accountId,
            @RequestParam(value = "type", required = false) String type) {
        Long targetAccountId = accountId;
        
        if (SecurityUtils.isPlatformAdmin() && targetAccountId == null) {
            return ResponseEntity.ok(List.of());
        }
        
        if (targetAccountId == null) {
            return ResponseEntity.badRequest().body(List.of());
        }
        
        List<GifFavorite> favorites = gifFavoriteService.getFavoritesByAccount(targetAccountId);
        // 按类型过滤
        if (type != null && !type.isEmpty()) {
            final String filterType = type;
            favorites = favorites.stream()
                    .filter(f -> filterType.equals(f.getType()))
                    .collect(Collectors.toList());
        }
        List<Map<String, Object>> result = favorites.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(result);
    }

    /**
     * 添加 GIF/Sticker 收藏
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addFavorite(@RequestBody Map<String, String> body) {
        String gifUrl = body.get("gifUrl");
        Long accountId = Long.parseLong(body.get("accountId"));
        String title = body.get("title");
        String type = body.getOrDefault("type", "gif");
        
        if (gifUrl == null || gifUrl.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL 不能为空"));
        }
        
        GifFavorite favorite = gifFavoriteService.addFavorite(accountId, gifUrl, title, type);
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
     * 检查 GIF/Sticker 是否已收藏
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkFavorited(
            @RequestParam Long accountId,
            @RequestParam String gifUrl,
            @RequestParam(value = "type", required = false) String type) {
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
        dto.put("type", favorite.getType() != null ? favorite.getType() : "gif");
        dto.put("createdAt", favorite.getCreatedAt());
        dto.put("accountId", favorite.getDiscordAccountId());
        return dto;
    }
}
