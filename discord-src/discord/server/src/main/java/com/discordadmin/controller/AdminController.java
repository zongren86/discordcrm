package com.discordadmin.controller;

import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.Conversation;
import com.discordadmin.discord.DiscordBotManager;
import com.discordadmin.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final DiscordAccountRepository accountRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final FriendRepository friendRepository;
    private final DiscordBotManager botManager;

    public AdminController(DiscordAccountRepository accountRepository,
                           ConversationRepository conversationRepository,
                           MessageRepository messageRepository,
                           FriendRepository friendRepository,
                           DiscordBotManager botManager) {
        this.accountRepository = accountRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.friendRepository = friendRepository;
        this.botManager = botManager;
    }

    @PostMapping("/clear-all")
    @Transactional
    public Map<String, Object> clearAll() {
        log.info("收到清空所有Discord数据请求");
        List<DiscordAccount> accounts = accountRepository.findAll();
        int accountCount = accounts.size();
        int convCount = 0;
        for (DiscordAccount account : accounts) {
            botManager.stopAccount(account.getId());
            List<Conversation> convs = conversationRepository.findByDiscordAccount(account);
            convCount += convs.size();
            for (Conversation conv : convs) {
                messageRepository.deleteByConversation(conv);
            }
            conversationRepository.deleteByDiscordAccount(account);
            friendRepository.deleteByDiscordAccount(account);
        }
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        friendRepository.deleteAll();
        accountRepository.deleteAll();
        log.info("清空所有Discord数据: {}个账号, {}个会话", accountCount, convCount);
        Map<String, Object> result = new HashMap<>();
        result.put("deletedAccounts", accountCount);
        result.put("deletedConversations", convCount);
        result.put("message", "已清空所有Discord账号及关联数据");
        return result;
    }
}
