package com.discordadmin.service;

import com.discordadmin.entity.ApkVersion;
import com.discordadmin.repository.ApkVersionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class ApkManagementService {

    private final ApkVersionRepository apkVersionRepository;
    private final MumuClientService mumuClientService;

    @Value("${discord.emulator.apk-path:${user.home}/.discord-emulator}")
    private String apkStoragePath;

    public ApkManagementService(ApkVersionRepository apkVersionRepository, MumuClientService mumuClientService) {
        this.apkVersionRepository = apkVersionRepository;
        this.mumuClientService = mumuClientService;
    }

    /**
     * 检查APK状态
     */
    public Map<String, Object> checkApkStatus() {
        Map<String, Object> result = new HashMap<>();
        String apkPath = getApkFilePath();
        File apkFile = new File(apkPath);

        boolean downloaded = apkFile.exists();
        result.put("downloaded", downloaded);

        if (downloaded) {
            result.put("fileSize", apkFile.length());
            result.put("lastModified", Instant.ofEpochMilli(apkFile.lastModified()).toString());

            // 获取激活版本信息
            apkVersionRepository.findByIsActiveTrue().ifPresent(version -> {
                result.put("version", version.getVersion());
                result.put("originalFilename", version.getOriginalFilename());
            });
        }

        return result;
    }

    /**
     * 上传APK文件（同时保存本地和上传到 MumuManager）
     */
    public Map<String, Object> uploadApk(MultipartFile file) throws IOException {
        // 确保目录存在
        Path storagePath = Paths.get(apkStoragePath);
        if (!Files.exists(storagePath)) {
            Files.createDirectories(storagePath);
        }

        // 保存APK文件
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.endsWith(".apk")) {
            throw new IllegalArgumentException("请上传.apk格式的文件");
        }

        String apkPath = getApkFilePath();
        file.transferTo(new File(apkPath));

        // 停用之前的版本
        apkVersionRepository.findByIsActiveTrue().ifPresent(prev -> {
            prev.setIsActive(false);
            apkVersionRepository.save(prev);
        });

        // 保存版本信息
        ApkVersion version = new ApkVersion();
        version.setVersion("v" + (System.currentTimeMillis() / 1000));
        version.setOriginalFilename(fileName);
        version.setStoragePath(apkPath);
        version.setFileSize(file.getSize());
        version.setIsActive(true);
        version.setUploadedAt(Instant.now());
        apkVersionRepository.save(version);

        // 上传到 MumuManager
        try {
            Map<String, Object> mumuResult = mumuClientService.uploadApk(file.getBytes(), fileName);
            log.info("上传 APK 到 MumuManager 结果: {}", mumuResult.get("message"));
        } catch (Exception e) {
            log.warn("上传 APK 到 MumuManager 失败: {}", e.getMessage());
            // 不影响主流程，继续返回成功
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "APK上传成功");
        result.put("fileSize", file.getSize());
        return result;
    }

    /**
     * 下载最新APK（模拟）
     */
    public Map<String, Object> downloadLatestApk() {
        Map<String, Object> result = new HashMap<>();

        String apkPath = getApkFilePath();
        File apkFile = new File(apkPath);

        if (apkFile.exists()) {
            result.put("success", true);
            result.put("message", "APK已存在");
        } else {
            result.put("success", false);
            result.put("message", "请手动上传Discord APK包");
        }

        return result;
    }

    /**
     * 获取APK文件路径
     */
    public String getApkFilePath() {
        return apkStoragePath + "/discord.apk";
    }

    /**
     * 检查APK是否存在
     */
    public boolean apkExists() {
        return new File(getApkFilePath()).exists();
    }
}
