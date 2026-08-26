package com.discordadmin.service;

import com.discordadmin.entity.*;
import com.discordadmin.repository.*;
import com.discordadmin.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EmuAccountBindingService {

    private final EmuAccountBindingRepository bindingRepository;
    private final DiscordAccountRepository accountRepository;
    private final MerchantRepository merchantRepository;
    private final OccupancyCheckService occupancyCheckService;
    private final AgentAccountNumberRelRepository relRepository;
    private final DiscordAccountNumberRepository numberRepository;

    public EmuAccountBindingService(EmuAccountBindingRepository bindingRepository,
                                     DiscordAccountRepository accountRepository,
                                     MerchantRepository merchantRepository,
                                     OccupancyCheckService occupancyCheckService,
                                     AgentAccountNumberRelRepository relRepository,
                                     DiscordAccountNumberRepository numberRepository) {
        this.bindingRepository = bindingRepository;
        this.accountRepository = accountRepository;
        this.merchantRepository = merchantRepository;
        this.occupancyCheckService = occupancyCheckService;
        this.relRepository = relRepository;
        this.numberRepository = numberRepository;
    }

    /**
     * 获取商户已添加的账号列表
     */
    public List<Map<String, Object>> getAddedAccounts(Long merchantId, Long userId) {
        List<EmuAccountBinding> bindings = bindingRepository.findByMerchantId(merchantId);
        List<Map<String, Object>> result = new ArrayList<>();

        // 批量查询账号编号信息
        Set<Long> accountIds = bindings.stream()
            .map(EmuAccountBinding::getDiscordAccountId)
            .collect(Collectors.toSet());
        Map<Long, List<DiscordAccountNumber>> numberMap = new HashMap<>();
        if (!accountIds.isEmpty()) {
            numberRepository.findByDiscordAccountIdIn(accountIds).forEach(num -> {
                numberMap.computeIfAbsent(num.getDiscordAccountId(), k -> new ArrayList<>()).add(num);
            });
        }

        for (EmuAccountBinding binding : bindings) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", binding.getId());
            item.put("discordAccountId", binding.getDiscordAccountId());
            item.put("status", binding.getStatus().name());
            item.put("statusText", getStatusText(binding.getStatus()));
            item.put("createdAt", binding.getCreatedAt());

            // 获取账号信息
            accountRepository.findById(binding.getDiscordAccountId()).ifPresent(account -> {
                item.put("accountName", account.getName());
                item.put("email", account.getEmail());
                item.put("discordName", account.getDiscordName());
                item.put("discordId", account.getDiscordId());
                item.put("accountStatus", account.getStatus().name());
                // Token有效性检查
                boolean tokenValid = checkTokenValidity(account);
                item.put("tokenValid", tokenValid);
                item.put("tokenValidText", tokenValid ? "有效" : "无效");
            });

            // 获取账号编号
            List<DiscordAccountNumber> numbers = numberMap.getOrDefault(binding.getDiscordAccountId(), Collections.emptyList());
            if (!numbers.isEmpty()) {
                item.put("numberId", numbers.get(0).getId());
                item.put("numberLabel", "编号#" + numbers.get(0).getId());
            }

            result.add(item);
        }
        return result;
    }

    /**
     * 获取可用的账号列表（未被当前商户添加，且未被占用）
     * 根据角色权限过滤：
     * - 商户管理员：获取商户所有账号
     * - 普通用户：获取其关联的账号
     */
    public List<Map<String, Object>> getAvailableAccounts(Long merchantId, Long userId, String keyword) {
        String role = SecurityUtils.currentRole();
        
        // 获取商户已添加的账号ID
        List<EmuAccountBinding> bindings = bindingRepository.findByMerchantId(merchantId);
        Set<Long> addedAccountIds = bindings.stream()
            .map(EmuAccountBinding::getDiscordAccountId)
            .collect(Collectors.toSet());

        // 获取被占用的账号ID
        Set<Long> occupiedAccountIds = occupancyCheckService.getOccupiedDiscordAccountIds();

        // 根据角色获取账号列表
        List<DiscordAccount> accounts;
        if ("MERCHANT_ADMIN".equals(role)) {
            // 商户管理员：获取商户所有账号
            accounts = accountRepository.findByMerchantId(merchantId);
        } else {
            // 普通用户：获取其关联的账号
            Long agentId = userId;
            List<AgentAccountNumberRel> rels = relRepository.findByAgentId(agentId);
            Set<Long> numberIds = rels.stream()
                .map(AgentAccountNumberRel::getAccountNumberId)
                .collect(Collectors.toSet());
            
            // 获取这些编号对应的Discord账号
            List<DiscordAccountNumber> numbers = numberRepository.findByIdIn(new ArrayList<>(numberIds));
            Set<Long> discordAccountIds = numbers.stream()
                .map(DiscordAccountNumber::getDiscordAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            
            if (discordAccountIds.isEmpty()) {
                return Collections.emptyList();
            }
            
            accounts = accountRepository.findAllById(discordAccountIds);
        }

        // 批量查询账号编号信息
        Map<Long, List<DiscordAccountNumber>> numberMap = new HashMap<>();
        Set<Long> candidateAccountIds = accounts.stream()
            .map(DiscordAccount::getId)
            .collect(Collectors.toSet());
        if (!candidateAccountIds.isEmpty()) {
            numberRepository.findByDiscordAccountIdIn(candidateAccountIds).forEach(num -> {
                numberMap.computeIfAbsent(num.getDiscordAccountId(), k -> new ArrayList<>()).add(num);
            });
        }

        return accounts.stream()
            .filter(a -> !addedAccountIds.contains(a.getId()))  // 未添加
            .filter(a -> !occupiedAccountIds.contains(a.getId()))  // 未被占用
            .filter(a -> keyword == null || keyword.isEmpty() ||
                (a.getName() != null && a.getName().toLowerCase().contains(keyword.toLowerCase())) ||
                (a.getEmail() != null && a.getEmail().toLowerCase().contains(keyword.toLowerCase())) ||
                (a.getDiscordName() != null && a.getDiscordName().toLowerCase().contains(keyword.toLowerCase())) ||
                String.valueOf(a.getId()).contains(keyword))
            .map(account -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", account.getId());
                item.put("accountName", account.getName());
                item.put("email", account.getEmail());
                item.put("discordName", account.getDiscordName());
                item.put("discordId", account.getDiscordId());
                item.put("accountStatus", account.getStatus().name());
                
                // Token有效性检查
                boolean tokenValid = checkTokenValidity(account);
                item.put("tokenValid", tokenValid);
                item.put("tokenValidText", tokenValid ? "有效" : "无效");
                item.put("canAdd", tokenValid);  // Token无效时不可添加

                // 获取账号编号
                List<DiscordAccountNumber> numbers = numberMap.getOrDefault(account.getId(), Collections.emptyList());
                if (!numbers.isEmpty()) {
                    item.put("numberId", numbers.get(0).getId());
                    item.put("numberLabel", "编号#" + numbers.get(0).getId());
                } else {
                    item.put("numberId", null);
                    item.put("numberLabel", "-");
                }

                return item;
            })
            .collect(Collectors.toList());
    }

    /**
     * 检查Token有效性
     * 基于账号状态和过期时间判断
     */
    private boolean checkTokenValidity(DiscordAccount account) {
        if (account.getStatus() == DiscordAccount.AccountStatus.INACTIVE) {
            return false;
        }
        if (account.getTokenExpiresAt() != null && account.getTokenExpiresAt().isBefore(Instant.now())) {
            return false;
        }
        return true;
    }

    /**
     * 添加账号绑定
     */
    @Transactional
    public EmuAccountBinding addAccount(Long merchantId, Long userId, Long discordAccountId) {
        // 检查账号是否存在
        DiscordAccount account = accountRepository.findById(discordAccountId)
            .orElseThrow(() -> new RuntimeException("账号不存在"));

        // 检查是否已添加
        boolean exists = bindingRepository.findByMerchantId(merchantId).stream()
            .anyMatch(b -> b.getDiscordAccountId().equals(discordAccountId));
        if (exists) {
            throw new RuntimeException("该账号已添加");
        }

        // 检查是否被占用
        if (occupancyCheckService.isDiscordAccountOccupied(discordAccountId)) {
            throw new RuntimeException("该账号已被其他进行中的任务占用");
        }

        EmuAccountBinding binding = new EmuAccountBinding();
        binding.setMerchantId(merchantId);
        binding.setUserId(userId);
        binding.setDiscordAccountId(discordAccountId);
        binding.setStatus(EmuAccountBinding.BindingStatus.ADDED);
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());

        return bindingRepository.save(binding);
    }

    /**
     * 移除账号绑定
     */
    @Transactional
    public void removeAccount(Long bindingId) {
        bindingRepository.deleteById(bindingId);
    }

    /**
     * 获取账号状态文本
     */
    private String getStatusText(EmuAccountBinding.BindingStatus status) {
        return switch (status) {
            case PENDING -> "待添加";
            case ADDED -> "已添加";
            case OCCUPIED -> "已占用";
        };
    }
}
