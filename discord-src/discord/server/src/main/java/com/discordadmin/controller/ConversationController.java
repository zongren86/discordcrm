package com.discordadmin.controller;

import com.discordadmin.dto.ConversationDtos.ConversationDto;
import com.discordadmin.dto.ConversationDtos.OpenDmRequest;
import com.discordadmin.dto.ConversationDtos.UpdatePinRequest;
import com.discordadmin.dto.ConversationDtos.UpdateRemarkRequest;
import com.discordadmin.dto.ConversationDtos.UpdateStageRequest;
import com.discordadmin.dto.ConversationDtos.UpdateStatusRequest;
import com.discordadmin.dto.MessageDtos.MessageDto;
import com.discordadmin.dto.MessageDtos.SendMessageRequest;
import com.discordadmin.dto.MessagePageDto;
import com.discordadmin.security.JwtAuthFilter.AuthenticatedAgent;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.ConversationService;
import com.discordadmin.translation.LanguageDetectionService;
import com.discordadmin.translation.TranslationServiceFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final LanguageDetectionService languageDetectionService;
    private final TranslationServiceFactory translationServiceFactory;

    public ConversationController(ConversationService conversationService, 
                                    LanguageDetectionService languageDetectionService,
                                    TranslationServiceFactory translationServiceFactory) {
        this.conversationService = conversationService;
        this.languageDetectionService = languageDetectionService;
        this.translationServiceFactory = translationServiceFactory;
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
            @RequestParam(required = false) Boolean pinnedOnly,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return conversationService.listConversations(accountId, stage, keyword, pinnedOnly, dateFrom, dateTo);
    }

    @GetMapping("/{id}/messages")
    public MessagePageDto listMessages(@PathVariable Long id,
                                       @RequestParam(required = false) Integer daysBack,
                                       @RequestParam(required = false) Integer pageSize) {
        return conversationService.listMessagesRecent(id, daysBack, pageSize);
    }

    /** 上滑加载更早一页（游标分页） */
    @GetMapping("/{id}/messages/older")
    public MessagePageDto listOlderMessages(@PathVariable Long id,
                                             @RequestParam Instant oldestCreatedAt,
                                             @RequestParam Long oldestId,
                                             @RequestParam(required = false) Integer pageSize) {
        return conversationService.listMessagesOlder(id, oldestCreatedAt, oldestId, pageSize);
    }

    @GetMapping("/{id}/messages/before/{msgId}")
    public List<MessageDto> loadMoreHistory(@PathVariable Long id, @PathVariable String msgId) {
        return conversationService.loadMoreHistory(id, msgId);
    }

    @PostMapping("/{id}/messages")
    public MessageDto sendMessage(@PathVariable Long id,
                                   @RequestBody SendMessageRequest request) {
        return conversationService.sendMessage(id, request.content(), request.targetLanguage(),
                request.messageType(), request.audioData(), request.audioMimeType(),
                request.audioDuration(), request.audioFileName(), getCurrentUsername(),
                request.attachments());
    }

    @PostMapping("/{id}/messages/gif")
    public MessageDto sendGifMessage(@PathVariable Long id,
                                     @RequestBody SendGifRequest request) {
        return conversationService.sendGifMessage(id, request.gifUrl(), request.title());
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

    @PostMapping("/{id}/mark-read")
    public ConversationDto markAsRead(@PathVariable Long id) {
        return conversationService.markAsRead(id);
    }

    @PostMapping("/{id}/messages/{messageId}/translate")
    public MessageDto translateMessage(@PathVariable Long id, @PathVariable Long messageId,
                                        @RequestParam(defaultValue = "zh-CN") String targetLanguage) {
        conversationService.loadOwnedConversation(id);
        return conversationService.translateMessage(messageId, targetLanguage);
    }

    /** 语音转文字：触发一次转写，INBOUND 自动翻译为中文 */
    @PostMapping("/{id}/messages/{messageId}/asr")
    public MessageDto transcribeVoice(@PathVariable Long id, @PathVariable Long messageId,
                                      @RequestParam(defaultValue = "false") Boolean autoTranslate) {
        conversationService.loadOwnedConversation(id);
        boolean at = Boolean.TRUE.equals(autoTranslate);
        return conversationService.transcribeAsr(messageId, at);
    }

    /** 对 ASR 出来的原文单独翻译 */
    @PostMapping("/{id}/messages/{messageId}/asr/translate")
    public MessageDto translateAsr(@PathVariable Long id, @PathVariable Long messageId,
                                   @RequestParam(defaultValue = "zh-CN") String targetLanguage) {
        conversationService.loadOwnedConversation(id);
        return conversationService.translateAsrText(messageId, targetLanguage);
    }

    @PostMapping("/detect-language")
    public Map<String, Object> detectLanguage(@RequestBody DetectLanguageRequest request) {
        Long merchantId = SecurityUtils.currentMerchantId();
        LanguageDetectionService.LanguageResult result = languageDetectionService.detect(request.text(), merchantId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", Map.of(
            "language", result.getCode(),
            "languageName", result.getName(),
            "confidence", result.getConfidence(),
            "detected", result.isDetected()
        ));
        return response;
    }

    /** 文本翻译：用于翻译预览等场景，直接翻译文本而不保存消息 */
    @PostMapping("/translate-text")
    public Map<String, Object> translateText(@RequestBody TranslateTextRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            Long merchantId = SecurityUtils.currentMerchantId();
            String translated = translationServiceFactory.translate(
                    request.text(), request.targetLanguage(), merchantId)
                    .orElse(request.text());
            response.put("code", 200);
            Map<String, Object> data = new HashMap<>();
            data.put("translatedText", translated);
            data.put("sourceText", request.text());
            data.put("targetLanguage", request.targetLanguage());
            response.put("data", data);
        } catch (Exception e) {
            response.put("code", 500);
            response.put("message", "翻译失败: " + e.getMessage());
        }
        return response;
    }

    /**
     * 获取 AI 翻译模型支持的所有语种列表
     */
    @GetMapping("/supported-languages")
    public Map<String, Object> getSupportedLanguages() {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", languageDetectionService.getSupportedLanguages());
        return response;
    }

    @PostMapping("/{id}/messages/{messageId}/detect-language")
    public MessageDto detectMessageLanguage(@PathVariable Long id, @PathVariable Long messageId) {
        conversationService.loadOwnedConversation(id);
        return conversationService.detectAndSetLanguage(messageId);
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
        return conversationService.replyMessage(id, request.content(), request.targetLanguage(),
                request.messageType(), request.audioData(), request.audioMimeType(),
                request.audioDuration(), request.audioFileName(), getCurrentUsername(), messageId);
    }

    /** 修复所有没有ownerAgentId的会话，尝试通过DiscordAccount关联查找并设置ownerAgentId */
    @PostMapping("/repair-owner-agent-ids")
    public Map<String, Object> repairOwnerAgentIds() {
        int updatedCount = conversationService.repairOwnerAgentIds();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("updatedCount", updatedCount);
        return response;
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
    public record DetectLanguageRequest(String text) {}
    public record SendGifRequest(String gifUrl, String title) {}
    public record TranslateTextRequest(String text, String targetLanguage) {}
}
