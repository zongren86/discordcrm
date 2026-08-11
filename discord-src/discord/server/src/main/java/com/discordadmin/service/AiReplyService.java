package com.discordadmin.service;

import com.discordadmin.entity.Message;
import com.discordadmin.repository.MessageRepository;
import com.discordadmin.translation.TranslationService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AiReplyService {

    private final MessageRepository messageRepository;
    private final TranslationService translationService;

    public AiReplyService(MessageRepository messageRepository, TranslationService translationService) {
        this.messageRepository = messageRepository;
        this.translationService = translationService;
    }

    /** 根据会话历史生成AI推荐回复（基于规则模板 + 翻译，不需要外部AI） */
    public List<Map<String, String>> suggestReplies(Long conversationId, String tone, int count) {
        List<Message> recentMsgs = messageRepository.findAll().stream()
                .filter(m -> m.getConversation() != null && m.getConversation().getId().equals(conversationId))
                .filter(m -> m.getDirection() == Message.Direction.INBOUND)
                .sorted(Comparator.comparing(Message::getCreatedAt).reversed())
                .limit(5)
                .collect(Collectors.toList());

        List<String> customerMessages = recentMsgs.stream()
                .map(m -> m.getContent())
                .filter(Objects::nonNull)
                .toList();

        List<Map<String, String>> suggestions = new ArrayList<>();
        String selectedTone = (tone != null) ? tone : "friendly";
        int selectedCount = Math.min(count > 0 ? count : 3, 5);

        List<String> templates = getTemplates(selectedTone, customerMessages);
        Collections.shuffle(templates);

        for (int i = 0; i < Math.min(selectedCount, templates.size()); i++) {
            String template = templates.get(i);
            Map<String, String> item = new HashMap<>();
            item.put("text", template);
            try {
                translationService.translate(template, "en")
                        .ifPresent(translated -> item.put("translated", translated));
            } catch (Exception e) {
                item.put("translated", template);
            }
            suggestions.add(item);
        }
        return suggestions;
    }

    private List<String> getTemplates(String tone, List<String> recentCustomerMsgs) {
        boolean hasGreeting = recentCustomerMsgs.stream()
                .anyMatch(m -> m != null && (m.toLowerCase().contains("hi") || m.toLowerCase().contains("hello") || m.contains("你好") || m.contains("在吗")));
        boolean hasQuestion = recentCustomerMsgs.stream()
                .anyMatch(m -> m != null && (m.contains("?") || m.contains("？") || m.toLowerCase().contains("how") || m.toLowerCase().contains("what") || m.toLowerCase().contains("?")));
        boolean hasPrice = recentCustomerMsgs.stream()
                .anyMatch(m -> m != null && (m.toLowerCase().contains("price") || m.toLowerCase().contains("cost") || m.contains("价格") || m.contains("多少钱") || m.toLowerCase().contains("?")));

        List<String> result = new ArrayList<>();
        switch (tone) {
            case "professional":
                if (hasGreeting) {
                    result.add("您好，很高兴认识您。请问有什么可以帮助您的？");
                    result.add("感谢您的联系。请详细描述您的需求，我将为您提供专业建议。");
                }
                if (hasPrice) {
                    result.add("关于价格问题，我们提供多种方案。请告诉我您的具体需求，我将为您报价。");
                    result.add("我们的产品有不同价格档位，具体取决于您的使用量和服务等级。");
                }
                if (hasQuestion) {
                    result.add("这是一个很好的问题。让我为您详细解答。");
                    result.add("根据您的描述，我建议我们进一步沟通以确定最佳方案。");
                }
                result.add("感谢您的耐心等待。请问还有其他问题吗？");
                result.add("期待您的回复，祝您拥有美好的一天。");
                break;
            case "casual":
                result.add("嗨！很高兴见到您 😊 有什么我可以帮您的吗？");
                result.add("嘿！看到您的消息啦～ 请问今天过得怎么样？");
                result.add("哈哈，感谢您的消息！让我们聊聊您的需求吧。");
                result.add("您好呀！有任何问题都可以随时问我哦～");
                break;
            default: // friendly
                if (hasGreeting) {
                    result.add("您好！很高兴认识您。请问有什么我可以帮您的？");
                    result.add("感谢您的联系！请告诉我您的需求，我会尽力帮助您。");
                }
                if (hasPrice) {
                    result.add("关于价格方面，我可以为您介绍不同的方案。请告诉我您的具体需求。");
                }
                if (hasQuestion) {
                    result.add("好问题！让我来为您解答。");
                    result.add("感谢提问。根据您的情况，我建议...");
                }
                result.add("感谢您的消息！我会尽快回复您。");
                result.add("还有其他我可以帮忙的吗？期待您的回复。");
                result.add("祝您有美好的一天！");
                break;
        }
        return result;
    }
}
