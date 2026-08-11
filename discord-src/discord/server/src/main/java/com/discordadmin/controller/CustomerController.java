package com.discordadmin.controller;

import com.discordadmin.dto.ConversationDtos.ConversationDto;
import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.DiscordUser;
import com.discordadmin.entity.Message;
import com.discordadmin.repository.ConversationRepository;
import com.discordadmin.repository.DiscordUserRepository;
import com.discordadmin.repository.MessageRepository;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.AiReplyService;
import com.discordadmin.service.MessageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final ConversationRepository conversationRepository;
    private final DiscordUserRepository discordUserRepository;
    private final MessageRepository messageRepository;
    private final AiReplyService aiReplyService;
    private final MessageService messageService;

    public CustomerController(ConversationRepository conversationRepository,
                              DiscordUserRepository discordUserRepository,
                              MessageRepository messageRepository,
                              AiReplyService aiReplyService,
                              MessageService messageService) {
        this.conversationRepository = conversationRepository;
        this.discordUserRepository = discordUserRepository;
        this.messageRepository = messageRepository;
        this.aiReplyService = aiReplyService;
        this.messageService = messageService;
    }

    /** 客户管理列表：所有可访问客户 */
    @GetMapping
    public List<Map<String, Object>> listCustomers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String stage) {
        Long merchantId = SecurityUtils.currentMerchantId();
        boolean isPlatformAdmin = SecurityUtils.isPlatformAdmin();

        List<Conversation> convs;
        if (isPlatformAdmin) {
            convs = conversationRepository.findByMerchantIdOrderByPinnedAndLastMessageAtDesc(null);
        } else {
            convs = conversationRepository.findByMerchantIdOrderByPinnedAndLastMessageAtDesc(merchantId);
        }
        if (tag != null && !tag.isBlank()) {
            convs = convs.stream()
                    .filter(c -> c.getDiscordUser() != null
                            && c.getDiscordUser().getTags() != null
                            && c.getDiscordUser().getTags().contains(tag))
                    .toList();
        }
        if (stage != null && !stage.isBlank()) {
            Conversation.Stage stageEnum = Conversation.Stage.valueOf(stage.toUpperCase());
            convs = convs.stream().filter(c -> stageEnum.equals(c.getStage())).toList();
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.toLowerCase();
            convs = convs.stream().filter(c -> {
                DiscordUser u = c.getDiscordUser();
                String name = u != null ? (u.getGlobalName() + " " + u.getUsername() + " " + u.getDiscordUserId()).toLowerCase() : "";
                String remark = c.getRemark() != null ? c.getRemark().toLowerCase() : "";
                return name.contains(kw) || remark.contains(kw);
            }).toList();
        }

        return convs.stream().map(c -> {
            Map<String, Object> item = new LinkedHashMap<>();
            DiscordUser u = c.getDiscordUser();
            item.put("conversationId", c.getId());
            item.put("userId", u != null ? u.getId() : null);
            item.put("discordUserId", u != null ? u.getDiscordUserId() : null);
            item.put("globalName", u != null ? u.getGlobalName() : null);
            item.put("username", u != null ? u.getUsername() : null);
            item.put("avatarUrl", u != null ? u.getAvatarUrl() : null);
            item.put("status", u != null && u.getStatus() != null ? u.getStatus().name() : "NORMAL");
            item.put("tags", u != null ? u.getTags() : null);
            item.put("notes", u != null ? u.getNotes() : null);
            item.put("stage", c.getStage() != null ? c.getStage().name() : null);
            item.put("remark", c.getRemark());
            item.put("lastMessageAt", c.getLastMessageAt());
            item.put("firstSeenAt", u != null ? u.getFirstSeenAt() : null);
            return item;
        }).toList();
    }

    /** 批量添加/移除标签 */
    @PostMapping("/batch-tags")
    public Map<String, Object> batchTags(@RequestBody BatchTagsRequest request) {
        Map<String, Object> result = new HashMap<>();
        int updated = 0;
        for (Long userId : request.userIds()) {
            DiscordUser user = discordUserRepository.findById(userId).orElse(null);
            if (user == null) continue;
            String currentTags = user.getTags() != null ? user.getTags() : "";
            Set<String> tagSet = new LinkedHashSet<>();
            for (String t : currentTags.split(",")) {
                if (!t.isBlank()) tagSet.add(t.trim());
            }
            if ("add".equals(request.action())) {
                for (String tag : request.tags()) tagSet.add(tag.trim());
            } else if ("remove".equals(request.action())) {
                for (String tag : request.tags()) tagSet.remove(tag.trim());
            }
            user.setTags(String.join(",", tagSet));
            discordUserRepository.save(user);
            updated++;
        }
        result.put("updated", updated);
        return result;
    }

    /** 批量更新阶段 */
    @PostMapping("/batch-stage")
    public Map<String, Object> batchStage(@RequestBody BatchStageRequest request) {
        Map<String, Object> result = new HashMap<>();
        int updated = 0;
        Conversation.Stage stage = Conversation.Stage.valueOf(request.stage());
        Instant now = Instant.now();
        for (Long convId : request.conversationIds()) {
            Conversation conv = conversationRepository.findById(convId).orElse(null);
            if (conv == null) continue;
            conv.setStage(stage);
            conv.setStageChangedAt(now);
            conversationRepository.save(conv);
            updated++;
        }
        result.put("updated", updated);
        return result;
    }

    /** 数据导出为JSON */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCustomers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) boolean includeMessages) {
        List<Map<String, Object>> customers = listCustomers(keyword, null, null);
        if (includeMessages) {
            for (Map<String, Object> cust : customers) {
                Long convId = (Long) cust.get("conversationId");
                if (convId != null) {
                    List<Message> msgs = messageRepository.findAll().stream()
                            .filter(m -> m.getConversation() != null && m.getConversation().getId().equals(convId))
                            .sorted(Comparator.comparing(Message::getCreatedAt))
                            .collect(Collectors.toList());
                    List<Map<String, Object>> msgList = new ArrayList<>();
                    for (Message m : msgs) {
                        Map<String, Object> mm = new LinkedHashMap<>();
                        mm.put("id", m.getId());
                        mm.put("direction", m.getDirection().name());
                        mm.put("content", m.getContent());
                        mm.put("translatedContent", m.getTranslatedContent());
                        mm.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
                        msgList.add(mm);
                    }
                    cust.put("messages", msgList);
                }
            }
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            byte[] json = om.writerWithDefaultPrettyPrinter().writeValueAsBytes(customers);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=customers.json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** AI推荐回复 */
    @GetMapping("/{id}/ai-suggestions")
    public List<Map<String, String>> getAiSuggestions(
            @PathVariable Long id,
            @RequestParam(defaultValue = "friendly") String tone,
            @RequestParam(defaultValue = "3") int count) {
        Conversation conv = conversationRepository.findById(id).orElse(null);
        if (conv == null) return List.of();
        return aiReplyService.suggestReplies(id, tone, count);
    }

    /** 批量发送消息到多个会话 */
    @PostMapping("/batch-send")
    public Map<String, Object> batchSendMessage(@RequestBody BatchSendRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        int success = 0;
        int failed = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        String senderName = getCurrentUsername();
        for (Long convId : request.conversationIds()) {
            try {
                Conversation conv = conversationRepository.findById(convId).orElse(null);
                if (conv == null) {
                    failed++;
                    details.add(Map.of("conversationId", convId, "success", false, "error", "会话不存在"));
                    continue;
                }
                SecurityUtils.checkMerchantAccess(conv.getMerchantId());
                messageService.sendReply(convId, request.content(), senderName);
                success++;
                details.add(Map.of("conversationId", convId, "success", true));
            } catch (Exception e) {
                failed++;
                details.add(Map.of("conversationId", convId, "success", false, "error", e.getMessage()));
            }
        }
        result.put("total", request.conversationIds().size());
        result.put("success", success);
        result.put("failed", failed);
        result.put("details", details);
        return result;
    }

    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.discordadmin.security.JwtAuthFilter.AuthenticatedAgent agent) {
            return agent.username();
        }
        return "客服";
    }

    public record BatchTagsRequest(List<Long> userIds, List<String> tags, String action) {}
    public record BatchStageRequest(List<Long> conversationIds, String stage) {}
    public record BatchSendRequest(List<Long> conversationIds, String content) {}
}
