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
    /**
     * 定时检查物理模拟器与数据库的一致性
     * 注意：此方法只记录日志提示，不自动创建或删除记录
     * 如需同步，请手动点击"同步"按钮
     */
    @Scheduled(fixedRate = 30000, initialDelay = 10000)
    public void syncAllPhysicalWithDb() {
        List<Map<String, Object>> physicalList;
        try {
            physicalList = mumuClientService.getAllEmulatorsWithError();
        } catch (Exception e) {
            log.debug("定时检查：Agent 不可达，跳过本次检查");
            return;
        }

        if (physicalList == null || physicalList.isEmpty()) {
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

        int missingInDb = 0;
        int missingInPhysical = 0;

        for (Map.Entry<String, List<EmuInstance>> entry : byMerchantUser.entrySet()) {
            List<EmuInstance> userInstances = entry.getValue();
            Set<Integer> dbIndices = userInstances.stream()
                .map(EmuInstance::getInstanceIndex)
                .collect(Collectors.toSet());

            String userId = userInstances.get(0).getUserId();

            // 1. 物理有但数据库没有 → 只提示
            for (int physIdx : physicalIndices) {
                if (!dbIndices.contains(physIdx)) {
                    missingInDb++;
                    log.warn("【定时检查】用户 {} 有物理模拟器 index={} 未在数据库中 - 请手动添加", userId, physIdx);
                }
            }

            // 2. 数据库有但物理没有 → 只提示
            for (EmuInstance dbInst : userInstances) {
                if (!physicalIndices.contains(dbInst.getInstanceIndex())) {
                    missingInPhysical++;
                    log.warn("【定时检查】用户 {} 的数据库记录 {} 在物理中不存在 - 如确认已删除可手动清理", userId, dbInst.getName());
                }
            }
        }

        // 汇总
        if (missingInDb > 0 || missingInPhysical > 0) {
            log.info("定时检查完成: {} 个物理未入库, {} 个数据库孤立记录", missingInDb, missingInPhysical);
        }
    }
}