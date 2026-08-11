package com.discordadmin.controller;

import com.discordadmin.dto.ConversationDtos.ConversationDto;
import com.discordadmin.dto.ConversationDtos.OpenDmRequest;
import com.discordadmin.dto.ConversationDtos.UpdatePinRequest;
import com.discordadmin.dto.ConversationDtos.UpdateRemarkRequest;
import com.discordadmin.dto.ConversationDtos.UpdateStageRequest;
import com.discordadmin.dto.ConversationDtos.UpdateStatusRequest;
import com.discordadmin.dto.MessageDtos.MessageDto;
import com.discordadmin.dto.MessageDtos.SendMessageRequest;
import com.discordadmin.security.JwtAuthFilter.AuthenticatedAgent;
import com.discordadmin.service.ConversationService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/open-dm")
    public ConversationDto openDm(@RequestBody OpenDmRequest request) {
        return conversationService.openDm(request);
    }

    @GetMapping
    public List<ConversationDto> listConversations(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean pinnedOnly) {
        return conversationService.listConversations(accountId, stage, keyword, pinnedOnly);
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> listMessages(@PathVariable Long id) {
        return conversationService.listMessages(id);
    }

    @GetMapping("/{id}/messages/before/{msgId}")
    public List<MessageDto> loadMoreHistory(@PathVariable Long id, @PathVariable String msgId) {
        return conversationService.loadMoreHistory(id, msgId);
    }

    @PostMapping("/{id}/messages")
    public MessageDto sendMessage(@PathVariable Long id,
                                   @RequestBody SendMessageRequest request) {
        return conversationService.sendMessage(id, request.content(), getCurrentUsername());
    }

    @PutMapping("/{id}/status")
    public ConversationDto updateStatus(@PathVariable Long id, @RequestBody UpdateStatusRequest request) {
        return conversationService.updateStatus(id, request.status());
    }

    @PutMapping("/{id}/assign")
    public ConversationDto assignToMe(@PathVariable Long id) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedAgent agent)) {
            throw new IllegalStateException("未登录");
        }
        return conversationService.assignToMe(id, agent.agentId());
    }

    @PutMapping("/{id}/assign/{agentId}")
    public ConversationDto assignToAgent(@PathVariable Long id, @PathVariable Long agentId) {
        return conversationService.assignToAgent(id, agentId);
    }

    @PutMapping("/{id}/transfer")
    public ConversationDto transferConversation(@PathVariable Long id,
                                                  @RequestBody TransferRequest request) {
        return conversationService.transferConversation(id, request.agentId(), request.reason());
    }

    @GetMapping("/agents")
    public List<Map<String, Object>> listAvailableAgents() {
        return conversationService.listAvailableAgents();
    }

    @PutMapping("/{id}/stage")
    public ConversationDto updateStage(@PathVariable Long id, @RequestBody UpdateStageRequest request) {
        return conversationService.updateStage(id, request.stage());
    }

    @PutMapping("/{id}/pin")
    public ConversationDto updatePin(@PathVariable Long id, @RequestBody UpdatePinRequest request) {
        return conversationService.updatePin(id, request.pinned());
    }

    @PutMapping("/{id}/remark")
    public ConversationDto updateRemark(@PathVariable Long id, @RequestBody UpdateRemarkRequest request) {
        return conversationService.updateRemark(id, request.remark());
    }

    @PostMapping("/{id}/messages/{messageId}/translate")
    public MessageDto translateMessage(@PathVariable Long id, @PathVariable Long messageId) {
        conversationService.loadOwnedConversation(id);
        return conversationService.translateMessage(messageId, "zh-CN");
    }

    @PutMapping("/{id}/messages/{messageId}")
    public MessageDto editMessage(@PathVariable Long id, @PathVariable Long messageId,
                                   @RequestBody EditMessageRequest request) {
        conversationService.loadOwnedConversation(id);
        return conversationService.editMessage(messageId, request.content());
    }

    @DeleteMapping("/{id}/messages/{messageId}")
    public MessageDto deleteMessage(@PathVariable Long id, @PathVariable Long messageId) {
        conversationService.loadOwnedConversation(id);
        return conversationService.deleteMessage(messageId);
    }

    @PostMapping("/{id}/messages/{messageId}/reactions")
    public MessageDto addReaction(@PathVariable Long id, @PathVariable Long messageId,
                                   @RequestBody ReactionRequest request) {
        conversationService.loadOwnedConversation(id);
        return conversationService.addReaction(messageId, request.emoji(), request.remove());
    }

    @PostMapping("/{id}/messages/{messageId}/reply")
    public MessageDto replyMessage(@PathVariable Long id, @PathVariable Long messageId,
                                    @RequestBody SendMessageRequest request) {
        return conversationService.replyMessage(id, request.content(), getCurrentUsername(), messageId);
    }

    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedAgent agent) {
            return agent.username();
        }
        return "客服";
    }

    public record EditMessageRequest(String content) {}
    public record ReactionRequest(String emoji, Boolean remove) {}
    public record TransferRequest(Long agentId, String reason) {}
}
