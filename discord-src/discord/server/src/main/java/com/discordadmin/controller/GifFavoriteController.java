package com.discordadmin.controller;

import com.discordadmin.entity.GifFavorite;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.GifFavoriteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Transactional(readOnly = true)
@RestController
@RequestMapping("/api/gif-favorites")
public class GifFavoriteController {

    private static final Logger log = LoggerFactory.getLogger(GifFavoriteController.class);

    private final GifFavoriteService gifFavoriteService;

    @Value("${app.upload.path:uploads}")
    private String uploadPath;

    @Value("${app.base-url:http://localhost:8090}")
    private String baseUrl;

    public GifFavoriteController(GifFavoriteService gifFavoriteService) {
        this.gifFavoriteService = gifFavoriteService;
    }

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
@Transactional

    @PostMapping
    public ResponseEntity<Map<String, Object>> addFavorite(@RequestBody Map<String, String> body) {
        String gifUrl = body.get("gifUrl");
        Long accountId = Long.parseLong(body.get("accountId"));
        String title = body.get("title");
        String type = body.getOrDefault("type", "gif");
        String convertedGifUrl = body.get("convertedGifUrl");
        
        if (gifUrl == null || gifUrl.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL 不能为空"));
        }
        
        Long currentUserId = SecurityUtils.currentUserId();
        GifFavorite favorite = gifFavoriteService.addFavorite(accountId, gifUrl, title, type, convertedGifUrl, currentUserId);
        return ResponseEntity.ok(toDto(favorite));
    }
@Transactional

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> removeFavorite(
            @PathVariable Long id,
            @RequestParam(required = false) Long accountId) {
        Long currentUserId = SecurityUtils.currentUserId();
        gifFavoriteService.removeFavorite(id, accountId, currentUserId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkFavorited(
            @RequestParam Long accountId,
            @RequestParam String gifUrl,
            @RequestParam(value = "type", required = false) String type) {
        Long currentUserId = SecurityUtils.currentUserId();
        boolean isFavorited = gifFavoriteService.isFavorited(accountId, currentUserId, gifUrl);
        Map<String, Object> result = new HashMap<>();
        result.put("favorited", isFavorited);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/normalize-url")
    public ResponseEntity<Map<String, String>> normalizeUrl(@RequestParam String url) {
        String normalized = gifFavoriteService.normalizeGifUrl(url);
        return ResponseEntity.ok(Map.of("originalUrl", url, "normalizedUrl", normalized));
    }
@Transactional

    @PostMapping("/upload-gif")
    public ResponseEntity<Map<String, String>> uploadGif(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不能为空"));
        }
        
        try {
            String originalFilename = file.getOriginalFilename();
            String ext = originalFilename != null && originalFilename.contains(".") 
                    ? originalFilename.substring(originalFilename.lastIndexOf(".") + 1) 
                    : "gif";
            
            String newFilename = "sticker_" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
            
            Path uploadDir = Paths.get(uploadPath, "stickers").toAbsolutePath();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }
            
            Path filePath = uploadDir.resolve(newFilename);
            Files.write(filePath, file.getBytes());
            
            String fileUrl = baseUrl + "/uploads/stickers/" + newFilename;
            
            log.info("GIF 文件上传成功: {}", fileUrl);
            return ResponseEntity.ok(Map.of("url", fileUrl, "filename", newFilename));
            
        } catch (IOException e) {
            log.error("GIF 文件上传失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "上传失败: " + e.getMessage()));
        }
    }

    private Map<String, Object> toDto(GifFavorite favorite) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", favorite.getId());
        dto.put("gifUrl", favorite.getGifUrl());
        dto.put("title", favorite.getTitle());
        dto.put("sourceDomain", favorite.getSourceDomain());
        dto.put("type", favorite.getType() != null ? favorite.getType() : "gif");
        dto.put("createdAt", favorite.getCreatedAt());
        dto.put("accountId", favorite.getDiscordAccountId());
        dto.put("userId", favorite.getUserId());
        dto.put("convertedGifUrl", favorite.getConvertedGifUrl());
        return dto;
    }
}
