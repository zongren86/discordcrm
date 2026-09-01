package com.discordadmin.controller;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return userService.list();
    }

    @PostMapping
    public Agent create(@RequestBody UserService.UserRequest req) {
        log.info("[UserController] create req: username={}, accountType={}, merchantId={}",
                req.username(), req.accountType(), req.merchantId());
        Agent result = userService.create(req);
        log.info("[UserController] create result: id={}, accountType={}, merchantId={}",
                result.getId(), result.getAccountType(), result.getMerchantId());
        return result;
    }

    @PutMapping("/{id}")
    public Agent update(@PathVariable Long id, @RequestBody UserService.UserRequest req) {
        log.info("[UserController] update id={} req: accountType={}, merchantId={}, clearMerchantId={}",
                id, req.accountType(), req.merchantId(), req.clearMerchantId());
        Agent result = userService.update(id, req);
        log.info("[UserController] update id={} result: accountType={}, merchantId={}",
                id, result.getAccountType(), result.getMerchantId());
        return result;
    }

    /** 配置用户角色（可多选） */
    @PutMapping("/{id}/roles")
    public Agent setRoles(@PathVariable Long id, @RequestBody Set<Long> roleIds) {
        return userService.setRoles(id, roleIds);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        userService.delete(id);
    }

    @GetMapping("/{id}/discord-accounts")
    public List<DiscordAccount> listLinkedAccounts(@PathVariable Long id) {
        return userService.listLinkedAccounts(id);
    }

    @PostMapping("/{id}/discord-accounts/{accountId}")
    public Agent linkAccount(@PathVariable Long id, @PathVariable Long accountId) {
        return userService.linkAccount(id, accountId);
    }

    @DeleteMapping("/{id}/discord-accounts/{accountId}")
    public Agent unlinkAccount(@PathVariable Long id, @PathVariable Long accountId) {
        return userService.unlinkAccount(id, accountId);
    }
}
