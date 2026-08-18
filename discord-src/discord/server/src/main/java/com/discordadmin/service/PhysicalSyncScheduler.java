package com.discordadmin.service;

import com.discordadmin.entity.EmuInstance;
import com.discordadmin.repository.EmuInstanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PhysicalSyncScheduler {

    private final MumuClientService mumuClientService;
    private final EmuInstanceRepository instanceRepository;

    public PhysicalSyncScheduler(MumuClientService mumuClientService,
                                  EmuInstanceRepository instanceRepository) {
        this.mumuClientService = mumuClientService;
        this.instanceRepository = instanceRepository;
    }

    /**
     * 每 30 秒检查一次物理模拟器与数据库的一致性
     * 启动延迟 10 秒，避免与启动时的心跳冲突
     */
    @Scheduled(fixedRate = 30000, initialDelay = 10000)
    @Transactional
    public void syncAllPhysicalWithDb() {
        List<Map<String, Object>> physicalList;
        try {
            physicalList = mumuClientService.getAllEmulatorsWithError();
        } catch (Exception e) {
            log.debug("定时同步：MumuManager 不可达，跳过本次同步");
            return;
        }

        if (physicalList == null) {
            return;
        }

        Set<Integer> physicalIndices = new HashSet<>();
        for (Map<String, Object> emu : physicalList) {
            Object idx = emu.get("index");
            if (idx instanceof Number) {
                physicalIndices.add(((Number) idx).intValue() + 1);
            }
        }

        List<EmuInstance> allDbInstances = instanceRepository.findAll();
        Map<String, List<EmuInstance>> byMerchantUser = allDbInstances.stream()
            .collect(Collectors.groupingBy(i -> i.getMerchantId() + ":" + (i.getUserId() != null ? i.getUserId() : "")));

        int created = 0;
        int cleaned = 0;

        for (Map.Entry<String, List<EmuInstance>> entry : byMerchantUser.entrySet()) {
            List<EmuInstance> userInstances = entry.getValue();
            Set<Integer> dbIndices = userInstances.stream()
                .map(EmuInstance::getInstanceIndex)
                .collect(Collectors.toSet());

            Long merchantId = userInstances.get(0).getMerchantId();
            String userId = userInstances.get(0).getUserId();

            // 1. 物理有但数据库没有 → 创建记录
            for (int physIdx : physicalIndices) {
                if (!dbIndices.contains(physIdx)) {
                    EmuInstance instance = new EmuInstance();
                    instance.setMerchantId(merchantId);
                    instance.setUserId(userId);
                    instance.setName("模拟器" + physIdx);
                    instance.setInstanceIndex(physIdx);
                    instance.setStatus(EmuInstance.EmuStatus.CREATED);
                    instance.setCpuCores(1);
                    instance.setMemoryGb(1);
                    instance.setResolution("720x1280");
                    instance.setDiscordInstalled(false);
                    instance.setDiscordLoggedIn(false);
                    instance.setAutoRunning(false);
                    instance.setAddedCount(0);
                    instance.setCreatedAt(Instant.now());
                    instanceRepository.save(instance);
                    created++;
                }
            }

            // 2. 数据库有但物理没有 → 清理记录
            for (EmuInstance dbInst : userInstances) {
                if (!physicalIndices.contains(dbInst.getInstanceIndex())) {
                    instanceRepository.delete(dbInst);
                    cleaned++;
                }
            }
        }

        if (created > 0 || cleaned > 0) {
            log.info("定时同步完成: 创建 {} 条记录, 清理 {} 条孤立记录", created, cleaned);
        }
    }
}