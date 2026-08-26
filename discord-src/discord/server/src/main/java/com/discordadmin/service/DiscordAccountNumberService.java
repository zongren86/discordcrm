package com.discordadmin.service;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordAccountNumber;
import com.discordadmin.entity.AccountBindingHistory;
import com.discordadmin.repository.AgentAccountNumberRelRepository;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.DiscordAccountNumberRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.AccountBindingHistoryRepository;
import com.discordadmin.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DiscordAccountNumberService {

    private final DiscordAccountNumberRepository accountNumberRepository;
    private final DiscordAccountRepository accountRepository;
    private final AccountBindingHistoryRepository bindingHistoryRepository;
    private final AgentRepository agentRepository;
    private final AgentAccountNumberRelRepository relRepository;

    public DiscordAccountNumberService(DiscordAccountNumberRepository accountNumberRepository,
                                        DiscordAccountRepository accountRepository,
                                        AccountBindingHistoryRepository bindingHistoryRepository,
                                        AgentRepository agentRepository,
                                        AgentAccountNumberRelRepository relRepository) {
        this.accountNumberRepository = accountNumberRepository;
        this.accountRepository = accountRepository;
        this.bindingHistoryRepository = bindingHistoryRepository;
        this.agentRepository = agentRepository;
        this.relRepository = relRepository;
    }

    /** 分页查询账号编号列表 */
    public Map<String, Object> list(String keyword, Instant startTime, Instant endTime, int page, int size) {
        boolean isPlatformAdmin = SecurityUtils.isPlatformAdmin();
        boolean isMerchantAdmin = SecurityUtils.isMerchantAdmin();
        Long currentAgentId = SecurityUtils.currentAgentId();
        Long merchantId = SecurityUtils.currentMerchantId();

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"));
        Page<DiscordAccountNumber> pageResult;

        // 平台管理员：可以查看所有商户的编号
        if (isPlatformAdmin) {
            pageResult = queryAll(keyword, startTime, endTime, pageable);
        }
        // 商户管理员：只能看到本商户的编号
        else if (isMerchantAdmin && merchantId != null) {
            pageResult = queryByMerchant(keyword, startTime, endTime, merchantId, pageable);
        }
        // 普通用户：只能看到分配给自己的编号（且必须属于本商户）
        else if (currentAgentId != null && merchantId != null) {
            List<Long> assignedNumberIds = relRepository.findAccountNumberIdsByAgentId(currentAgentId);
            Map<String, Object> empty = new HashMap<>();
            empty.put("content", List.of());
            empty.put("totalElements", 0);
            empty.put("totalPages", 0);
            empty.put("number", 0);
            empty.put("size", size);
            if (assignedNumberIds.isEmpty()) {
                return empty;
            }
            pageResult = queryByMerchantAndIds(keyword, startTime, endTime, merchantId, assignedNumberIds, pageable);
        }
        // 没有权限的用户返回空
        else {
            Map<String, Object> empty = new HashMap<>();
            empty.put("content", List.of());
            empty.put("totalElements", 0);
            empty.put("totalPages", 0);
            empty.put("number", 0);
            empty.put("size", size);
            return empty;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("content", convertToDTOList(pageResult.getContent()));
        result.put("totalElements", pageResult.getTotalElements());
        result.put("totalPages", pageResult.getTotalPages());
        result.put("number", pageResult.getNumber());
        result.put("size", pageResult.getSize());
        return result;
    }

    /** 平台管理员：查询所有编号 */
    private Page<DiscordAccountNumber> queryAll(String keyword, Instant startTime, Instant endTime, Pageable pageable) {
        if (keyword != null && !keyword.trim().isEmpty() && startTime != null && endTime != null) {
            return accountNumberRepository.searchByKeywordAndTimeRange(keyword, startTime, endTime, pageable);
        } else if (keyword != null && !keyword.trim().isEmpty()) {
            return accountNumberRepository.searchByKeyword(keyword, pageable);
        } else if (startTime != null && endTime != null) {
            return accountNumberRepository.findByTimeRange(startTime, endTime, pageable);
        }
        return accountNumberRepository.findAll(pageable);
    }

    /** 商户管理员：查询本商户的编号 */
    private Page<DiscordAccountNumber> queryByMerchant(String keyword, Instant startTime, Instant endTime, Long merchantId, Pageable pageable) {
        if (keyword != null && !keyword.trim().isEmpty() && startTime != null && endTime != null) {
            return accountNumberRepository.searchByKeywordAndTimeRangeAndMerchantId(keyword, startTime, endTime, merchantId, pageable);
        } else if (keyword != null && !keyword.trim().isEmpty()) {
            return accountNumberRepository.searchByKeywordAndMerchantId(keyword, merchantId, pageable);
        } else if (startTime != null && endTime != null) {
            return accountNumberRepository.findByTimeRangeAndMerchantId(startTime, endTime, merchantId, pageable);
        }
        return accountNumberRepository.findByMerchantId(merchantId, pageable);
    }

    /** 普通用户：查询本商户+分配给我的编号 */
    private Page<DiscordAccountNumber> queryByMerchantAndIds(String keyword, Instant startTime, Instant endTime,
                                                              Long merchantId, List<Long> assignedIds, Pageable pageable) {
        if (keyword != null && !keyword.trim().isEmpty() && startTime != null && endTime != null) {
            return accountNumberRepository.searchByKeywordAndTimeRangeAndMerchantIdAndIdIn(keyword, startTime, endTime, merchantId, assignedIds, pageable);
        } else if (keyword != null && !keyword.trim().isEmpty()) {
            return accountNumberRepository.searchByKeywordAndMerchantIdAndIdIn(keyword, merchantId, assignedIds, pageable);
        } else if (startTime != null && endTime != null) {
            return accountNumberRepository.findByTimeRangeAndMerchantIdAndIdIn(startTime, endTime, merchantId, assignedIds, pageable);
        }
        return accountNumberRepository.findByMerchantIdAndIdIn(merchantId, assignedIds, pageable);
    }

    private List<Map<String, Object>> convertToDTOList(List<DiscordAccountNumber> numbers) {
        List<Long> accountIds = numbers.stream()
                .map(DiscordAccountNumber::getDiscordAccountId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, DiscordAccount> accountMap = accountIds.isEmpty() ? new HashMap<>() :
                accountRepository.findByIdIn(accountIds).stream()
                        .collect(Collectors.toMap(DiscordAccount::getId, a -> a));

        return numbers.stream().map(n -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", n.getId());
            map.put("customNo", n.getCustomNo());
            map.put("discordAccountId", n.getDiscordAccountId());
            map.put("boundAccount", n.getBoundAccount());
            map.put("creatorId", n.getCreatorId());
            map.put("creatorName", n.getCreatorName());
            map.put("createdAt", n.getCreatedAt());
            map.put("updatedAt", n.getUpdatedAt());

            DiscordAccount account = n.getDiscordAccountId() != null ? accountMap.get(n.getDiscordAccountId()) : null;
            map.put("accountName", account != null ? account.getName() : null);
            map.put("accountEmail", account != null ? account.getEmail() : null);
            return map;
        }).toList();
    }

    /** 批量创建账号编号 */
    @Transactional
    public List<DiscordAccountNumber> batchCreate(List<String> accounts) {
        Long agentId = SecurityUtils.currentAgentId();
        Long merchantId = SecurityUtils.currentMerchantId();
        Agent currentUser = agentId != null ? agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("当前用户不存在")) : null;
        String creatorName = currentUser != null && currentUser.getDisplayName() != null ? currentUser.getDisplayName() : (currentUser != null ? currentUser.getUsername() : "系统");

        Integer maxNo = accountNumberRepository.findMaxCustomNoByMerchantId(merchantId);
        int nextNo = (maxNo == null ? 0 : maxNo) + 1;
        List<DiscordAccountNumber> numbers = new ArrayList<>();
        for (String account : accounts) {
            account = account.trim();
            if (!account.isEmpty()) {
                DiscordAccountNumber num = new DiscordAccountNumber();
                num.setCustomNo(nextNo++);
                num.setBoundAccount(account);
                num.setMerchantId(merchantId);  // 设置商户ID
                num.setCreatorId(currentUser != null ? currentUser.getId() : null);
                num.setCreatorName(creatorName);
                numbers.add(num);
            }
        }
        List<DiscordAccountNumber> saved = accountNumberRepository.saveAll(numbers);

        // 自动关联创建者（普通用户）：确保创建后能在自己的列表中看到
        if (currentUser != null) {
            boolean isAdmin = SecurityUtils.isAdmin();
            if (!isAdmin) {
                Instant now = Instant.now();
                List<com.discordadmin.entity.AgentAccountNumberRel> rels = new ArrayList<>();
                for (DiscordAccountNumber n : saved) {
                    com.discordadmin.entity.AgentAccountNumberRel rel = new com.discordadmin.entity.AgentAccountNumberRel();
                    rel.setAgentId(currentUser.getId());
                    rel.setAccountNumberId(n.getId());
                    rel.setLinkedAt(now);
                    rel.setCreatedAt(now);
                    rels.add(rel);
                }
                relRepository.saveAll(rels);
            }
        }
        return saved;
    }

    /** 按数量生成空编号（用户名和邮箱为空，后续可绑定） */
    @Transactional
    public List<DiscordAccountNumber> generate(int quantity) {
        Long agentId = SecurityUtils.currentAgentId();
        Long merchantId = SecurityUtils.currentMerchantId();
        Agent currentUser = agentId != null ? agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("当前用户不存在")) : null;
        String creatorName = currentUser != null && currentUser.getDisplayName() != null ? currentUser.getDisplayName() : (currentUser != null ? currentUser.getUsername() : "系统");

        Integer maxNo = accountNumberRepository.findMaxCustomNoByMerchantId(merchantId);
        int nextNo = (maxNo == null ? 0 : maxNo) + 1;
        List<DiscordAccountNumber> numbers = new ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            DiscordAccountNumber num = new DiscordAccountNumber();
            num.setCustomNo(nextNo++);
            num.setBoundAccount(null);
            num.setMerchantId(merchantId);  // 设置商户ID
            num.setCreatorId(currentUser != null ? currentUser.getId() : null);
            num.setCreatorName(creatorName);
            numbers.add(num);
        }
        List<DiscordAccountNumber> saved = accountNumberRepository.saveAll(numbers);

        // 自动关联创建者（普通用户）：确保创建后能在自己的列表中看到
        if (currentUser != null) {
            boolean isAdmin = SecurityUtils.isAdmin();
            if (!isAdmin) {
                Instant now = Instant.now();
                List<com.discordadmin.entity.AgentAccountNumberRel> rels = new ArrayList<>();
                for (DiscordAccountNumber n : saved) {
                    com.discordadmin.entity.AgentAccountNumberRel rel = new com.discordadmin.entity.AgentAccountNumberRel();
                    rel.setAgentId(currentUser.getId());
                    rel.setAccountNumberId(n.getId());
                    rel.setLinkedAt(now);
                    rel.setCreatedAt(now);
                    rels.add(rel);
                }
                relRepository.saveAll(rels);
            }
        }
        return saved;
    }

    /** 绑定账号 */
    @Transactional
    public DiscordAccountNumber bindAccount(Long id, String newAccount, Long discordAccountId, String changeReason) {
        DiscordAccountNumber num = accountNumberRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("账号编号不存在"));

        String oldAccount = num.getBoundAccount();
        Long agentId = SecurityUtils.currentAgentId();
        Agent currentUser = agentId != null ? agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("当前用户不存在")) : null;
        String operatorName = currentUser != null && currentUser.getDisplayName() != null ? currentUser.getDisplayName() : (currentUser != null ? currentUser.getUsername() : "系统");

        num.setBoundAccount(newAccount);
        num.setDiscordAccountId(discordAccountId);
        num.setUpdatedAt(Instant.now());
        DiscordAccountNumber saved = accountNumberRepository.save(num);

        // 记录绑定历史
        AccountBindingHistory history = new AccountBindingHistory();
        history.setAccountNumberId(id);
        history.setOldAccount(oldAccount);
        history.setNewAccount(newAccount);
        history.setChangeReason(changeReason);
        history.setOperatorId(currentUser != null ? currentUser.getId() : null);
        history.setOperatorName(operatorName);
        history.setChangedAt(Instant.now());
        bindingHistoryRepository.save(history);

        return saved;
    }

    /** 查询绑定历史 */
    public List<AccountBindingHistory> getBindingHistory(Long accountNumberId) {
        return bindingHistoryRepository.findByAccountNumberIdOrderByChangedAtDesc(accountNumberId);
    }

    /** 查询未绑定的账号列表（用于绑定时的下拉选择）- 只显示当前商户下的未绑定账号 */
    public List<Map<String, Object>> listUnboundAccounts(String keyword) {
        // 找出已被绑定的 discordAccountId
        List<DiscordAccountNumber> boundNumbers = accountNumberRepository.findAll();
        Set<Long> boundAccountIds = boundNumbers.stream()
                .map(DiscordAccountNumber::getDiscordAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 获取当前用户的商户ID
        Long merchantId = SecurityUtils.currentMerchantId();
        boolean isPlatformAdmin = SecurityUtils.isPlatformAdmin();

        // 查询账号：只显示当前商户下的账号（商户管理员）或所有账号（平台管理员）
        List<DiscordAccount> accounts;
        if (isPlatformAdmin) {
            accounts = accountRepository.findAll();
        } else if (merchantId != null) {
            accounts = accountRepository.findByMerchantIdOrNull(merchantId);
        } else {
            accounts = accountRepository.findByMerchantIdIsNull();
        }

        return accounts.stream()
                .filter(a -> !boundAccountIds.contains(a.getId()))
                .filter(a -> keyword == null || keyword.trim().isEmpty() ||
                        a.getName().toLowerCase().contains(keyword.toLowerCase()) ||
                        (a.getEmail() != null && a.getEmail().toLowerCase().contains(keyword.toLowerCase())))
                .map(a -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", a.getId());
                    map.put("name", a.getName());
                    map.put("email", a.getEmail());
                    map.put("remark", a.getRemark());
                    return map;
                })
                .collect(Collectors.toList());
    }

    /** 校验当前用户是否有权访问指定编号 */
    private void checkAccess(Long numberId) {
        DiscordAccountNumber num = accountNumberRepository.findById(numberId)
                .orElseThrow(() -> new IllegalArgumentException("账号编号不存在"));
        
        Long merchantId = SecurityUtils.currentMerchantId();
        Long currentAgentId = SecurityUtils.currentAgentId();

        // 平台管理员可以访问所有编号
        if (SecurityUtils.isPlatformAdmin()) {
            return;
        }

        // 商户管理员只能访问本商户的编号
        if (SecurityUtils.isMerchantAdmin()) {
            if (merchantId != null && merchantId.equals(num.getMerchantId())) {
                return;
            }
            throw new org.springframework.security.access.AccessDeniedException("无权访问该编号");
        }

        // 普通用户：只能访问本商户+分配给自己的编号
        if (currentAgentId != null && merchantId != null) {
            // 检查是否属于本商户
            if (!merchantId.equals(num.getMerchantId())) {
                throw new org.springframework.security.access.AccessDeniedException("无权访问该编号");
            }
            // 检查是否分配给自己
            List<Long> assignedNumberIds = relRepository.findAccountNumberIdsByAgentId(currentAgentId);
            if (assignedNumberIds.contains(numberId)) {
                return;
            }
        }
        throw new org.springframework.security.access.AccessDeniedException("无权访问该编号");
    }

    /** 根据ID查询 */
    public DiscordAccountNumber findById(Long id) {
        checkAccess(id);
        return accountNumberRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("账号编号不存在"));
    }

    /** 解绑账号（清除绑定的DiscordAccount，保留编号记录） */
    @Transactional
    public DiscordAccountNumber unbindAccount(Long id) {
        checkAccess(id);
        DiscordAccountNumber num = accountNumberRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("账号编号不存在"));
        
        String oldAccount = num.getBoundAccount();
        Long agentId = SecurityUtils.currentAgentId();
        Agent currentUser = agentId != null ? agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("当前用户不存在")) : null;
        String operatorName = currentUser != null && currentUser.getDisplayName() != null ? currentUser.getDisplayName() : (currentUser != null ? currentUser.getUsername() : "系统");

        num.setDiscordAccountId(null);
        num.setBoundAccount(null);
        num.setUpdatedAt(Instant.now());
        DiscordAccountNumber saved = accountNumberRepository.save(num);

        // 记录解绑历史
        AccountBindingHistory history = new AccountBindingHistory();
        history.setAccountNumberId(id);
        history.setOldAccount(oldAccount);
        history.setNewAccount(null);
        history.setChangeReason("解绑账号");
        history.setOperatorId(currentUser != null ? currentUser.getId() : null);
        history.setOperatorName(operatorName);
        history.setChangedAt(Instant.now());
        bindingHistoryRepository.save(history);

        return saved;
    }

    /** 删除账号编号 */
    @Transactional
    public void deleteAccountNumber(Long id) {
        checkAccess(id);
        DiscordAccountNumber num = accountNumberRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("账号编号不存在"));
        accountNumberRepository.delete(num);
    }
}
