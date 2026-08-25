package com.discordadmin.config;

import com.discordadmin.entity.AgentRegistration;
import com.discordadmin.repository.AgentRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 数据清理器
 * 在应用启动时清理重复的 Agent 注册记录（同一 userId+deviceId 只能有一条）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentDataCleaner implements ApplicationRunner {

    private final AgentRegistrationRepository agentRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("开始清理 Agent 重复记录...");
        
        List<AgentRegistration> allAgents = agentRepository.findAll();
        Map<String, List<AgentRegistration>> groupedByUserDevice = allAgents.stream()
            .collect(Collectors.groupingBy(a -> a.getUserId() + "_" + a.getDeviceId()));
        
        int totalRemoved = 0;
        
        for (Map.Entry<String, List<AgentRegistration>> entry : groupedByUserDevice.entrySet()) {
            List<AgentRegistration> duplicates = entry.getValue();
            if (duplicates.size() > 1) {
                String[] parts = entry.getKey().split("_", 2);
                log.warn("发现重复 Agent 记录: userId={}, deviceId={}, count={}", 
                    parts[0], parts[1], duplicates.size());
                
                // 按 id 排序，保留最早的（id 最小的），删除其他重复记录
                duplicates.sort(Comparator.comparing(AgentRegistration::getId));
                AgentRegistration keeper = duplicates.get(0);
                
                for (int i = 1; i < duplicates.size(); i++) {
                    AgentRegistration toRemove = duplicates.get(i);
                    agentRepository.delete(toRemove);
                    totalRemoved++;
                    log.info("删除重复 Agent 记录: id={}, userId={}, deviceId={}", 
                        toRemove.getId(), toRemove.getUserId(), toRemove.getDeviceId());
                }
                
                log.info("保留 Agent 记录: id={}, userId={}, deviceId={}", 
                    keeper.getId(), keeper.getUserId(), keeper.getDeviceId());
            }
        }
        
        // 将所有离线状态的 Agent 记录清理掉
        List<AgentRegistration> offlineAgents = agentRepository.findByStatus("OFFLINE");
        int offlineRemoved = 0;
        for (AgentRegistration agent : offlineAgents) {
            agentRepository.delete(agent);
            offlineRemoved++;
        }
        if (offlineRemoved > 0) {
            log.info("清理离线 Agent 记录: {} 条", offlineRemoved);
        }
        
        int totalKept = agentRepository.findAll().size();
        log.info("Agent 数据清理完成: 删除重复 {} 条，删除离线 {} 条，保留 {} 条", 
            totalRemoved, offlineRemoved, totalKept);
    }
}
