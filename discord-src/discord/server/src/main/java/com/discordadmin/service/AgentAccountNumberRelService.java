package com.discordadmin.service;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.AgentAccountNumberRel;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordAccountNumber;
import com.discordadmin.repository.AgentAccountNumberRelRepository;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.DiscordAccountNumberRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AgentAccountNumberRelService {

    private final AgentAccountNumberRelRepository relRepository;
    private final AgentRepository agentRepository;
    private final DiscordAccountNumberRepository accountNumberRepository;
    private final DiscordAccountRepository accountRepository;
    private final ConversationService conversationService;

    public AgentAccountNumberRelService(AgentAccountNumberRelRepository relRepository,
                                        AgentRepository agentRepository,
                                        DiscordAccountNumberRepository accountNumberRepository,
                                        DiscordAccountRepository accountRepository,
                                        ConversationService conversationService) {
        this.relRepository = relRepository;
        this.agentRepository = agentRepository;
        this.accountNumberRepository = accountNumberRepository;
        this.accountRepository = accountRepository;
        this.conversationService = conversationService;
    }

    /** 获取用户关联的账号编号列表 */
    public List<Map<String, Object>> listByAgentId(Long agentId) {
        List<AgentAccountNumberRel> rels = relRepository.findByAgentId(agentId);

        List<Long> numberIds = rels.stream()
                .map(AgentAccountNumberRel::getAccountNumberId)
                .toList();

        Map<Long, DiscordAccountNumber> numberMap = numberIds.isEmpty() ? new HashMap<>() :
                accountNumberRepository.findByIdIn(numberIds).stream()
                        .collect(Collectors.toMap(DiscordAccountNumber::getId, n -> n));

        return rels.stream().map(rel -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", rel.getId());
            map.put("accountNumberId", rel.getAccountNumberId());
            map.put("linkedAt", rel.getLinkedAt());

            DiscordAccountNumber num = numberMap.get(rel.getAccountNumberId());
            if (num != null) {
                map.put("id", num.getId());
                map.put("customNo", num.getCustomNo());
                map.put("number", num.getCustomNo());
                map.put("boundAccount", num.getBoundAccount());
                map.put("discordAccountId", num.getDiscordAccountId());
            }
            return map;
        }).toList();
    }

    /** 批量关联账号编号给用户 */
    @Transactional
    public void batchLinkNumbers(Long agentId, List<Long> numberIds) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // 检查是否已被其他用户关联
        for (Long numberId : numberIds) {
            List<AgentAccountNumberRel> existingRels = relRepository.findByAccountNumberId(numberId);
            for (AgentAccountNumberRel rel : existingRels) {
                if (!rel.getAgentId().equals(agentId)) {
                    Agent otherAgent = agentRepository.findById(rel.getAgentId()).orElse(null);
                    String ownerName = otherAgent != null && otherAgent.getDisplayName() != null 
                            ? otherAgent.getDisplayName() 
                            : (otherAgent != null ? otherAgent.getUsername() : "未知用户");
                    throw new IllegalStateException("账号编号「" + numberId + "」已被用户「" + ownerName + "」关联，一个账号只能关联一个用户");
                }
            }
        }

        // 查询已关联的编号ID
        List<Long> existingNumberIds = relRepository.findAccountNumberIdsByAgentId(agentId);
        Set<Long> existingSet = new HashSet<>(existingNumberIds);

        // 新增不在已有范围内的编号
        List<AgentAccountNumberRel> toAdd = new ArrayList<>();
        for (Long numberId : numberIds) {
            if (!existingSet.contains(numberId)) {
                // 检查编号是否存在
                DiscordAccountNumber num = accountNumberRepository.findById(numberId)
                        .orElseThrow(() -> new IllegalArgumentException("账号编号不存在: " + numberId));

                AgentAccountNumberRel rel = new AgentAccountNumberRel();
                rel.setAgentId(agentId);
                rel.setAccountNumberId(numberId);
                rel.setLinkedAt(Instant.now());
                toAdd.add(rel);
            }
        }
        if (!toAdd.isEmpty()) {
            relRepository.saveAll(toAdd);
        }

        // 删除已关联但不在新范围内的编号
        List<Long> numberIdsToKeep = new ArrayList<>(numberIds);
        numberIdsToKeep.add(0L); // 防止空列表问题
        relRepository.deleteByAgentIdAndAccountNumberIdNotIn(agentId, numberIdsToKeep);

        // 修复关联的会话ownerAgentId
        conversationService.repairOwnerAgentIds();
    }

    /** 删除单条关联 */
    @Transactional
    public void unlinkNumber(Long agentId, Long accountNumberId) {
        relRepository.deleteByAgentIdAndAccountNumberId(agentId, accountNumberId);
    }

    /** 解析编号范围字符串 */
    public List<Long> parseNumberRange(String rangeStr) {
        Set<Long> result = new LinkedHashSet<>();
        if (rangeStr == null || rangeStr.trim().isEmpty()) {
            return new ArrayList<>();
        }

        String[] parts = rangeStr.split("[,，\\s]+");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            if (part.contains("-") || part.contains("~")) {
                String[] range = part.split("[-~]");
                if (range.length == 2) {
                    try {
                        long start = Long.parseLong(range[0].trim());
                        long end = Long.parseLong(range[1].trim());
                        long min = Math.min(start, end);
                        long max = Math.max(start, end);
                        for (long i = min; i <= max; i++) {
                            result.add(i);
                        }
                    } catch (NumberFormatException e) {
                        // 忽略无效格式
                    }
                }
            } else {
                try {
                    result.add(Long.parseLong(part));
                } catch (NumberFormatException e) {
                    // 忽略无效格式
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** 获取账号关联的所有用户（用于 Discord 账号管理页面） */
    public List<Map<String, Object>> getAgentsByAccountNumber(Long accountNumberId) {
        List<AgentAccountNumberRel> rels = relRepository.findByAccountNumberId(accountNumberId);
        List<Long> agentIds = rels.stream()
                .map(AgentAccountNumberRel::getAgentId)
                .distinct()
                .toList();

        Map<Long, Agent> agentMap = agentIds.isEmpty() ? new HashMap<>() :
                agentRepository.findAllById(agentIds).stream()
                        .collect(Collectors.toMap(Agent::getId, a -> a));

        return rels.stream().map(rel -> {
            Map<String, Object> map = new HashMap<>();
            map.put("relId", rel.getId());
            map.put("agentId", rel.getAgentId());
            map.put("linkedAt", rel.getLinkedAt());

            Agent agent = agentMap.get(rel.getAgentId());
            if (agent != null) {
                map.put("agentName", agent.getDisplayName() != null ? agent.getDisplayName() : agent.getUsername());
                map.put("agentUsername", agent.getUsername());
            }
            return map;
        }).toList();
    }

    /** 批量按自定义编号关联账号编号给用户 */
    @Transactional
    public void batchLinkByCustomNos(Long agentId, List<Integer> customNos) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        Long merchantId = agent.getMerchantId();
        if (merchantId == null) {
            throw new IllegalArgumentException("用户未关联商户，无法关联账号编号");
        }

        // 按商户和自定义编号查询 DiscordAccountNumber
        List<DiscordAccountNumber> numbers = accountNumberRepository.findByMerchantIdAndCustomNoIn(merchantId, customNos);
        if (numbers.size() != customNos.size()) {
            // 找出不存在的 customNo
            Set<Integer> foundNos = numbers.stream()
                    .map(DiscordAccountNumber::getCustomNo)
                    .collect(Collectors.toSet());
            List<Integer> missingNos = customNos.stream()
                    .filter(n -> !foundNos.contains(n))
                    .toList();
            throw new IllegalArgumentException("以下自定义编号在当前商户下不存在: " + missingNos);
        }

        List<Long> numberIds = numbers.stream()
                .map(DiscordAccountNumber::getId)
                .toList();

        batchLinkNumbers(agentId, numberIds);
    }
}