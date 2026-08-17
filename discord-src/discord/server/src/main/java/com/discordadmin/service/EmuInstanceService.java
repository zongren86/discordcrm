package com.discordadmin.service;

import com.discordadmin.entity.EmuInstance;
import com.discordadmin.repository.EmuInstanceRepository;
import com.discordadmin.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EmuInstanceService {

    private final EmuInstanceRepository instanceRepository;

    public EmuInstanceService(EmuInstanceRepository instanceRepository) {
        this.instanceRepository = instanceRepository;
    }

    /**
     * 获取当前用户的所有模拟器实例
     */
    public List<Map<String, Object>> getCurrentUserInstances() {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();
        List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        return instances.stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
    }

    /**
     * 设置模拟器数量
     */
    @Transactional
    public List<Map<String, Object>> setInstanceCount(int count, int cpuCores, int memoryGb) {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();

        List<EmuInstance> existing = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);

        // 如果需要减少数量，删除多余的
        if (existing.size() > count) {
            for (int i = count; i < existing.size(); i++) {
                instanceRepository.delete(existing.get(i));
            }
        }

        // 如果需要增加数量，创建新的
        int maxIndex = existing.stream()
            .mapToInt(EmuInstance::getInstanceIndex)
            .max()
            .orElse(0);

        while (existing.size() < count) {
            maxIndex++;
            EmuInstance instance = new EmuInstance();
            instance.setMerchantId(merchantId);
            instance.setUserId(userId);
            instance.setName("模拟器" + maxIndex);
            instance.setInstanceIndex(maxIndex);
            instance.setStatus(EmuInstance.EmuStatus.CREATED);
            instance.setCpuCores(cpuCores);
            instance.setMemoryGb(memoryGb);
            instance.setResolution("720x1280");
            instance.setDiscordInstalled(false);
            instance.setDiscordLoggedIn(false);
            instance.setAutoRunning(false);
            instance.setAddedCount(0);
            instance.setCreatedAt(Instant.now());
            existing.add(instanceRepository.save(instance));
        }

        return existing.stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
    }

    /**
     * 启动模拟器
     */
    @Transactional
    public Map<String, Object> startInstance(int index) {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndInstanceIndex(merchantId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        // 检查APK是否已上传
        boolean apkExists = checkApkExists();
        if (!apkExists) {
            instance.setLastError("请先上传Discord APK包");
            instance.setStatus(EmuInstance.EmuStatus.ERROR);
            instanceRepository.save(instance);
            throw new RuntimeException("请先上传Discord APK包");
        }

        instance.setStatus(EmuInstance.EmuStatus.RUNNING);
        instance.setLastError(null);
        instance.setUpdatedAt(Instant.now());
        
        // 如果已安装Discord，默认打开Discord应用
        if (instance.getDiscordInstalled()) {
            instance.setDiscordOnHome(false); // 启动时需要检查是否在首页
            instance.setLastError("模拟器已启动，请打开Discord应用");
        }
        
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 停止模拟器
     */
    @Transactional
    public Map<String, Object> stopInstance(int index) {
        Long merchantId = SecurityUtils.currentMerchantId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndInstanceIndex(merchantId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        instance.setStatus(EmuInstance.EmuStatus.STOPPED);
        instance.setAutoRunning(false);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 重启模拟器
     */
    @Transactional
    public Map<String, Object> restartInstance(int index) {
        Long merchantId = SecurityUtils.currentMerchantId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndInstanceIndex(merchantId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        instance.setStatus(EmuInstance.EmuStatus.RUNNING);
        instance.setLastError(null);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 启动所有模拟器
     */
    @Transactional
    public List<Map<String, Object>> startAllInstances() {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();

        boolean apkExists = checkApkExists();
        if (!apkExists) {
            throw new RuntimeException("请先上传Discord APK包");
        }

        List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        for (EmuInstance inst : instances) {
            inst.setStatus(EmuInstance.EmuStatus.RUNNING);
            inst.setLastError(null);
            inst.setUpdatedAt(Instant.now());
            instanceRepository.save(inst);
        }

        return instances.stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
    }

    /**
     * 停止所有模拟器
     */
    @Transactional
    public List<Map<String, Object>> stopAllInstances() {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();

        List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        for (EmuInstance inst : instances) {
            inst.setStatus(EmuInstance.EmuStatus.STOPPED);
            inst.setAutoRunning(false);
            inst.setUpdatedAt(Instant.now());
            instanceRepository.save(inst);
        }

        return instances.stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
    }

    /**
     * 安装Discord到模拟器
     */
    @Transactional
    public Map<String, Object> installDiscord(int index) {
        Long merchantId = SecurityUtils.currentMerchantId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndInstanceIndex(merchantId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        if (instance.getStatus() != EmuInstance.EmuStatus.RUNNING) {
            throw new RuntimeException("模拟器未运行");
        }

        boolean apkExists = checkApkExists();
        if (!apkExists) {
            throw new RuntimeException("请先上传Discord APK包");
        }

        instance.setDiscordInstalled(true);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 启动Discord应用
     */
    @Transactional
    public Map<String, Object> launchDiscord(int index) {
        Long merchantId = SecurityUtils.currentMerchantId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndInstanceIndex(merchantId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        if (!instance.getDiscordInstalled()) {
            throw new RuntimeException("Discord未安装");
        }

        if (!instance.getDiscordLoggedIn()) {
            instance.setLastError("Discord未登录，请先在模拟器中登录Discord");
            instanceRepository.save(instance);
            throw new RuntimeException("Discord未登录，请先在模拟器中登录Discord");
        }

        // 启动Discord后，需要用户确保进入首页
        // 首页是指有好友列表和添加好友按钮的页面
        instance.setDiscordOnHome(false); // 重置首页状态，需要模拟器检查
        instance.setLastError(null);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        Map<String, Object> result = new HashMap<>();
        result.put("result", "Discord已启动，请确保进入首页（有好友列表和添加好友按钮的页面）");
        return result;
    }

    /**
     * 更新Discord首页状态（由模拟器调用）
     */
    @Transactional
    public Map<String, Object> updateDiscordHomeStatus(int index, boolean onHome) {
        Long merchantId = SecurityUtils.currentMerchantId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndInstanceIndex(merchantId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        instance.setDiscordOnHome(onHome);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        Map<String, Object> result = new HashMap<>();
        result.put("onHome", onHome);
        result.put("success", true);
        return result;
    }

    /**
     * 标记Discord登录状态（由模拟器调用）
     */
    @Transactional
    public Map<String, Object> updateDiscordLoginStatus(int index, boolean loggedIn) {
        Long merchantId = SecurityUtils.currentMerchantId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndInstanceIndex(merchantId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        instance.setDiscordLoggedIn(loggedIn);
        if (!loggedIn) {
            instance.setDiscordOnHome(false); // 退出登录后重置首页状态
        }
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        Map<String, Object> result = new HashMap<>();
        result.put("loggedIn", loggedIn);
        result.put("success", true);
        return result;
    }

    /**
     * 启动自动加好友
     */
    @Transactional
    public Map<String, Object> startAutoAdd(int index) {
        Long merchantId = SecurityUtils.currentMerchantId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndInstanceIndex(merchantId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        // 检查模拟器状态
        if (instance.getStatus() != EmuInstance.EmuStatus.RUNNING) {
            throw new RuntimeException("模拟器未运行");
        }

        // 检查Discord是否安装
        if (!instance.getDiscordInstalled()) {
            throw new RuntimeException("Discord未安装");
        }

        // 检查Discord是否登录
        if (!instance.getDiscordLoggedIn()) {
            instance.setLastError("Discord未登录");
            instanceRepository.save(instance);
            throw new RuntimeException("Discord未登录，请先在模拟器中登录Discord");
        }

        // 检查是否在首页
        if (!instance.getDiscordOnHome()) {
            instance.setLastError("Discord未在首页，请先跳转到首页（有好友列表和添加好友按钮的页面）");
            instanceRepository.save(instance);
            throw new RuntimeException("Discord未在首页，请先跳转到首页");
        }

        instance.setAutoRunning(true);
        instance.setLastError(null);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 停止自动加好友
     */
    @Transactional
    public Map<String, Object> stopAutoAdd(int index) {
        Long merchantId = SecurityUtils.currentMerchantId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndInstanceIndex(merchantId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        instance.setAutoRunning(false);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 全部启动自动加好友
     */
    @Transactional
    public List<Map<String, Object>> startAllAutoAdd() {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();

        List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        List<String> errors = new ArrayList<>();

        for (EmuInstance inst : instances) {
            if (inst.getStatus() == EmuInstance.EmuStatus.RUNNING 
                && inst.getDiscordInstalled() 
                && inst.getDiscordLoggedIn()
                && !inst.getAutoRunning()) {
                inst.setAutoRunning(true);
                inst.setLastError(null);
                inst.setUpdatedAt(Instant.now());
                instanceRepository.save(inst);
            } else if (inst.getStatus() == EmuInstance.EmuStatus.RUNNING && !inst.getDiscordLoggedIn()) {
                errors.add("#" + inst.getInstanceIndex() + " Discord未登录");
            }
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException("部分模拟器无法启动: " + String.join("; ", errors));
        }

        return instances.stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
    }

    /**
     * 全部停止自动加好友
     */
    @Transactional
    public List<Map<String, Object>> stopAllAutoAdd() {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();

        List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        for (EmuInstance inst : instances) {
            inst.setAutoRunning(false);
            inst.setUpdatedAt(Instant.now());
            instanceRepository.save(inst);
        }

        return instances.stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
    }

    /**
     * 删除模拟器实例
     */
    @Transactional
    public void deleteInstance(int index) {
        Long merchantId = SecurityUtils.currentMerchantId();
        instanceRepository.findByMerchantIdAndInstanceIndex(merchantId, index)
            .ifPresent(instanceRepository::delete);
    }

    /**
     * 检查APK是否存在
     */
    private boolean checkApkExists() {
        String apkPath = System.getProperty("user.home") + "/.discord-emulator/discord.apk";
        return new java.io.File(apkPath).exists();
    }

    /**
     * 转换为Map
     */
    private Map<String, Object> convertToMap(EmuInstance instance) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", instance.getId());
        item.put("index", instance.getInstanceIndex());
        item.put("name", instance.getName());
        item.put("status", instance.getStatus().name());
        item.put("cpuCores", instance.getCpuCores());
        item.put("memoryGb", instance.getMemoryGb());
        item.put("resolution", instance.getResolution());
        item.put("adbPort", instance.getAdbPort());
        item.put("discordInstalled", instance.getDiscordInstalled());
        item.put("discordLoggedIn", instance.getDiscordLoggedIn());
        item.put("discordOnHome", instance.getDiscordOnHome());
        item.put("discordAccount", instance.getDiscordAccountId());
        item.put("autoRunning", instance.getAutoRunning());
        item.put("addedCount", instance.getAddedCount());
        item.put("nextAddAt", instance.getNextAddAt() != null ? instance.getNextAddAt().toEpochMilli() : null);
        item.put("lastError", instance.getLastError());
        item.put("autoLastResult", instance.getAutoLastResult());
        return item;
    }
}
