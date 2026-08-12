package com.discordadmin.controller;

import com.discordadmin.entity.AccountBindingHistory;
import com.discordadmin.entity.DiscordAccountNumber;
import com.discordadmin.service.DiscordAccountNumberService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/account-numbers")
public class DiscordAccountNumberController {

    private final DiscordAccountNumberService accountNumberService;

    public DiscordAccountNumberController(DiscordAccountNumberService accountNumberService) {
        this.accountNumberService = accountNumberService;
    }

    /** 分页查询账号编号列表 */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Instant start = startTime != null ? Instant.ofEpochMilli(startTime) : null;
        Instant end = endTime != null ? Instant.ofEpochMilli(endTime) : null;
        return accountNumberService.list(keyword, start, end, page, size);
    }

    /** 批量创建账号编号 */
    @PostMapping("/batch")
    public List<DiscordAccountNumber> batchCreate(@RequestBody Map<String, List<String>> body) {
        List<String> accounts = body.get("accounts");
        if (accounts == null || accounts.isEmpty()) {
            throw new IllegalArgumentException("账号列表不能为空");
        }
        return accountNumberService.batchCreate(accounts);
    }

    /** 绑定账号 */
    @PutMapping("/{id}/bind")
    public DiscordAccountNumber bindAccount(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        String newAccount = (String) body.get("newAccount");
        Long discordAccountId = body.get("discordAccountId") != null ? 
                Long.valueOf(body.get("discordAccountId").toString()) : null;
        String changeReason = (String) body.get("changeReason");
        
        return accountNumberService.bindAccount(id, newAccount, discordAccountId, changeReason);
    }

    /** 查询绑定历史 */
    @GetMapping("/{id}/history")
    public List<AccountBindingHistory> getBindingHistory(@PathVariable Long id) {
        return accountNumberService.getBindingHistory(id);
    }

    /** 查询未绑定的账号列表（用于绑定时的下拉选择） */
    @GetMapping("/unbound-accounts")
    public List<Map<String, Object>> listUnboundAccounts(
            @RequestParam(required = false) String keyword) {
        return accountNumberService.listUnboundAccounts(keyword);
    }

    /** 根据ID查询 */
    @GetMapping("/{id}")
    public DiscordAccountNumber findById(@PathVariable Long id) {
        return accountNumberService.findById(id);
    }
}
