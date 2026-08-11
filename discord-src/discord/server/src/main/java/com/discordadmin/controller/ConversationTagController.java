package com.discordadmin.controller;

import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.ConversationTag;
import com.discordadmin.repository.ConversationRepository;
import com.discordadmin.repository.ConversationTagRepository;
import com.discordadmin.security.SecurityUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/conversation-tags")
public class ConversationTagController {

    private final ConversationTagRepository tagRepository;
    private final ConversationRepository conversationRepository;

    public ConversationTagController(ConversationTagRepository tagRepository,
                                      ConversationRepository conversationRepository) {
        this.tagRepository = tagRepository;
        this.conversationRepository = conversationRepository;
    }

    /** 获取会话的所有标签 */
    @GetMapping("/conversation/{conversationId}")
    public List<Map<String, Object>> getByConversation(@PathVariable Long conversationId) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        SecurityUtils.checkMerchantAccess(conv.getMerchantId());

        return tagRepository.findByConversationId(conversationId).stream()
                .map(this::toMap).toList();
    }

    /** 获取商户下所有可用的标签名称 */
    @GetMapping("/names")
    public List<String> listTagNames() {
        Long merchantId = SecurityUtils.currentMerchantId();
        return tagRepository.findDistinctNamesByMerchantId(
                SecurityUtils.isPlatformAdmin() ? null : merchantId);
    }

    /** 为会话添加标签 */
    @PostMapping("/conversation/{conversationId}")
    public List<Map<String, Object>> addTags(@PathVariable Long conversationId,
                                              @RequestBody AddTagsRequest request) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        SecurityUtils.checkMerchantAccess(conv.getMerchantId());

        Long merchantId = conv.getMerchantId();
        List<String> existingNames = tagRepository.findByConversationId(conversationId)
                .stream().map(ConversationTag::getName).collect(Collectors.toList());

        for (String tagName : request.tags()) {
            if (!existingNames.contains(tagName)) {
                ConversationTag tag = new ConversationTag();
                tag.setConversationId(conversationId);
                tag.setMerchantId(merchantId);
                tag.setName(tagName);
                tag.setColor(request.color());
                tagRepository.save(tag);
            }
        }

        return tagRepository.findByConversationId(conversationId).stream()
                .map(this::toMap).toList();
    }

    /** 删除会话的某个标签 */
    @DeleteMapping("/conversation/{conversationId}/{tagId}")
    public List<Map<String, Object>> removeTag(@PathVariable Long conversationId,
                                                 @PathVariable Long tagId) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        SecurityUtils.checkMerchantAccess(conv.getMerchantId());

        tagRepository.deleteById(tagId);

        return tagRepository.findByConversationId(conversationId).stream()
                .map(this::toMap).toList();
    }

    /** 批量设置会话标签（覆盖） */
    @PutMapping("/conversation/{conversationId}")
    public List<Map<String, Object>> setTags(@PathVariable Long conversationId,
                                              @RequestBody AddTagsRequest request) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        SecurityUtils.checkMerchantAccess(conv.getMerchantId());

        tagRepository.deleteByConversationId(conversationId);

        Long merchantId = conv.getMerchantId();
        for (String tagName : request.tags()) {
            ConversationTag tag = new ConversationTag();
            tag.setConversationId(conversationId);
            tag.setMerchantId(merchantId);
            tag.setName(tagName);
            tag.setColor(request.color());
            tagRepository.save(tag);
        }

        return tagRepository.findByConversationId(conversationId).stream()
                .map(this::toMap).toList();
    }

    /** 按标签筛选会话 */
    @GetMapping("/filter")
    public List<Long> filterByTag(@RequestParam String tagName) {
        Long merchantId = SecurityUtils.currentMerchantId();
        List<ConversationTag> tags;
        if (SecurityUtils.isPlatformAdmin()) {
            tags = tagRepository.findByMerchantId(null);
        } else {
            tags = tagRepository.findByMerchantId(merchantId);
        }
        return tags.stream()
                .filter(t -> t.getName().equals(tagName))
                .map(ConversationTag::getConversationId)
                .distinct()
                .toList();
    }

    private Map<String, Object> toMap(ConversationTag tag) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", tag.getId());
        item.put("conversationId", tag.getConversationId());
        item.put("name", tag.getName());
        item.put("color", tag.getColor());
        return item;
    }

    public record AddTagsRequest(List<String> tags, String color) {}
}
