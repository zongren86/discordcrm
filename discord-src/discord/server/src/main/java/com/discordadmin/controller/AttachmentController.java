package com.discordadmin.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    @Value("${app.upload.path:uploads}")
    private String uploadDir;


    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (file.isEmpty()) {
                result.put("success", false);
                result.put("error", "文件为空");
                return result;
            }
            String originalFilename = file.getOriginalFilename();
            String ext = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                ext = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String newFilename = UUID.randomUUID().toString() + ext;
            Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path path = dir.resolve(newFilename);
            file.transferTo(path);
            result.put("success", true);
            result.put("filename", originalFilename);
            result.put("url", "/api/attachments/download/" + newFilename);
            result.put("size", file.getSize());
            result.put("contentType", file.getContentType());
        } catch (IOException e) {
            result.put("success", false);
            result.put("error", "上传失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/download/{filename}")
    public ResponseEntity<Resource> download(@PathVariable String filename) {
        try {
            Path path = Paths.get(uploadDir).resolve(filename).normalize();
            if (!Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }
            File file = path.toFile();
            String contentType = Files.probeContentType(path);
            // 添加 CORS 头，允许前端跨域加载图片
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType != null ? contentType : "application/octet-stream"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"")
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                    .body(new FileSystemResource(file));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
