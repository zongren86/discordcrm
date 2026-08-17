package com.discordadmin.service;

import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.EmuAccountBinding;
import com.discordadmin.entity.Merchant;
import com.discordadmin.repository.*;
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

    public EmuAccountBindingService(EmuAccountBindingRepository bindingRepository,
                                     DiscordAccountRepository accountRepository,
                                     MerchantRepository merchantRepository,
                                     OccupancyCheckService occupancyCheckService) {
        this.bindingRepository = bindingRepository;
        this.accountRepository = accountRepository;
        this.merchantRepository = merchantRepository;
        this.occupancyCheckService = occupancyCheckService;
    }

    /**
     * 获取商户已添加的账号列表
     */
    public List<Map<String, Object>> getAddedAccounts(Long merchantId, String userId) {
        List<EmuAccountBinding> bindings = bindingRepository.findByMerchantId(merchantId);
        List<Map<String, Object>> result = new ArrayList<>();

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
                item.put("status", account.getStatus().name());
            });

            result.add(item);
        }
        return result;
    }

    /**
     * 获取可用的账号列表（未被当前商户添加，且未被占用）
     */
    public List<Map<String, Object>> getAvailableAccounts(Long merchantId, String userId, String keyword) {
        // 获取商户已添加的账号ID
        List<EmuAccountBinding> bindings = bindingRepository.findByMerchantId(merchantId);
        Set<Long> addedAccountIds = bindings.stream()
            .map(EmuAccountBinding::getDiscordAccountId)
            .collect(Collectors.toSet());

        // 获取被占用的账号ID
        Set<Long> occupiedAccountIds = occupancyCheckService.getOccupiedDiscordAccountIds();

        // 查询所有账号（属于当前商户或全局账号）
        List<DiscordAccount> accounts = accountRepository.findByMerchantId(merchantId);

        return accounts.stream()
            .filter(a -> !addedAccountIds.contains(a.getId()))  // 未添加
            .filter(a -> !occupiedAccountIds.contains(a.getId()))  // 未被占用
            .filter(a -> keyword == null || keyword.isEmpty() ||
                (a.getName() != null && a.getName().toLowerCase().contains(keyword.toLowerCase())) ||
                (a.getEmail() != null && a.getEmail().toLowerCase().contains(keyword.toLowerCase())) ||
                (a.getDiscordName() != null && a.getDiscordName().toLowerCase().contains(keyword.toLowerCase())))
            .map(account -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", account.getId());
                item.put("accountName", account.getName());
                item.put("email", account.getEmail());
                item.put("discordName", account.getDiscordName());
                item.put("discordId", account.getDiscordId());
                item.put("status", account.getStatus().name());
                item.put("canAdd", true);
                return item;
            })
            .collect(Collectors.toList());
    }

    /**
     * 添加账号绑定
     */
    @Transactional
    public EmuAccountBinding addAccount(Long merchantId, String userId, Long discordAccountId) {
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
