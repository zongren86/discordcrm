package com.discordadmin.controller;

import com.discordadmin.dto.DiscordAccountDtos.*;
import com.discordadmin.service.DiscordAccountService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/discord-accounts")
public class DiscordAccountController {

    private final DiscordAccountService accountService;

    public DiscordAccountController(DiscordAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public List<AccountDto> listAccounts(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String status) {
        return accountService.listAccounts(keyword, status);
    }

    @PostMapping
    public AccountDto createAccount(@RequestBody CreateAccountRequest request) {
        return accountService.createAccount(request);
    }

    /** 手工添加：粘贴解析后按 Discord ID 做 upsert（存在=更新，不存在=新增） */
    @PostMapping("/upsert-by-discord-id")
    public UpsertResponse upsertByDiscordId(@RequestBody UpsertAccountByDiscordIdRequest request) {
        return accountService.upsertByDiscordId(request);
    }

    @PostMapping("/import-token")
    public ImportTokenResponse importToken(@RequestBody ImportTokenRequest request) {
        return accountService.importToken(request);
    }

    @PutMapping("/{id}")
    public AccountDto updateAccount(@PathVariable Long id, @RequestBody UpdateAccountRequest request) {
        return accountService.updateAccount(id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteAccount(@PathVariable Long id) {
        accountService.deleteAccount(id);
    }

    @PostMapping("/{id}/connect")
    public AccountDto connect(@PathVariable Long id) {
        return accountService.connect(id);
    }

    @PostMapping("/{id}/disconnect")
    public AccountDto disconnect(@PathVariable Long id) {
        return accountService.disconnect(id);
    }

    @PostMapping("/batch-import")
    public BatchImportResponse batchImport(@RequestBody BatchImportRequest request) {
        return accountService.batchImport(request);
    }

    @PostMapping("/{id}/refresh-token")
    public RefreshTokenResponse refreshToken(@PathVariable Long id,
                                              @RequestBody RefreshTokenRequest request) {
        return accountService.refreshToken(id, request);
    }
}
