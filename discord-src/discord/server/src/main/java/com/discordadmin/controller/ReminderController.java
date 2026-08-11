package com.discordadmin.controller;

import com.discordadmin.entity.Notification;
import com.discordadmin.entity.ReminderRule;
import com.discordadmin.repository.NotificationRepository;
import com.discordadmin.repository.ReminderRuleRepository;
import com.discordadmin.security.JwtAuthFilter.AuthenticatedAgent;
import com.discordadmin.security.SecurityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    private final ReminderRuleRepository reminderRuleRepository;
    private final NotificationRepository notificationRepository;

    public ReminderController(ReminderRuleRepository reminderRuleRepository,
                              NotificationRepository notificationRepository) {
        this.reminderRuleRepository = reminderRuleRepository;
        this.notificationRepository = notificationRepository;
    }

    @GetMapping("/rules")
    public List<ReminderRule> listRules() {
        Long merchantId = SecurityUtils.currentMerchantId();
        if (SecurityUtils.isPlatformAdmin()) {
            return reminderRuleRepository.findByMerchantIdIsNullOrderByIdDesc();
        }
        return reminderRuleRepository.findByMerchantIdOrderByIdDesc(merchantId);
    }

    @PostMapping("/rules")
    public ReminderRule createRule(@RequestBody ReminderRequest req) {
        Long merchantId = SecurityUtils.currentMerchantId();
        if (!SecurityUtils.isPlatformAdmin()) {
            merchantId = SecurityUtils.currentMerchantId();
        }
        ReminderRule rule = new ReminderRule();
        rule.setName(req.name());
        rule.setTriggerType(req.triggerType());
        rule.setTriggerConfig(req.triggerConfig());
        rule.setFrequency(req.frequency());
        rule.setChannel(req.channel() != null ? req.channel() : "system");
        rule.setMessageTemplate(req.messageTemplate());
        rule.setEnabled(req.enabled() != null ? req.enabled() : true);
        rule.setMerchantId(merchantId);
        return reminderRuleRepository.save(rule);
    }

    @PutMapping("/rules/{id}")
    public ReminderRule updateRule(@PathVariable Long id, @RequestBody ReminderRequest req) {
        ReminderRule rule = reminderRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在"));
        if (req.name() != null) rule.setName(req.name());
        if (req.triggerType() != null) rule.setTriggerType(req.triggerType());
        if (req.triggerConfig() != null) rule.setTriggerConfig(req.triggerConfig());
        if (req.frequency() != null) rule.setFrequency(req.frequency());
        if (req.messageTemplate() != null) rule.setMessageTemplate(req.messageTemplate());
        if (req.enabled() != null) rule.setEnabled(req.enabled());
        return reminderRuleRepository.save(rule);
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable Long id) {
        reminderRuleRepository.deleteById(id);
        return Map.of("success", true);
    }

    @GetMapping("/notifications")
    public List<Notification> listNotifications() {
        Long agentId = currentAgentId();
        if (agentId == null) return List.of();
        return notificationRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Object> unreadCount() {
        Long agentId = currentAgentId();
        long count = agentId == null ? 0 : notificationRepository.countByAgentIdAndIsReadFalse(agentId);
        return Map.of("count", count);
    }

    @PostMapping("/notifications/{id}/read")
    public Map<String, Object> markRead(@PathVariable Long id) {
        Notification n = notificationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("通知不存在"));
        n.setIsRead(true);
        notificationRepository.save(n);
        return Map.of("success", true);
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> markAllRead() {
        Long agentId = currentAgentId();
        if (agentId != null) {
            List<Notification> list = notificationRepository.findByAgentIdAndIsReadFalseOrderByCreatedAtDesc(agentId);
            for (Notification n : list) {
                n.setIsRead(true);
                notificationRepository.save(n);
            }
        }
        return Map.of("success", true);
    }

    @PostMapping("/notifications/test")
    public Map<String, Object> sendTest(@RequestBody TestNotificationRequest req) {
        Long agentId = currentAgentId();
        if (agentId == null) return Map.of("success", false, "error", "未登录");
        Notification n = new Notification();
        n.setAgentId(agentId);
        n.setMerchantId(SecurityUtils.currentMerchantId());
        n.setType(req.type() != null ? req.type() : "system");
        n.setTitle(req.title() != null ? req.title() : "测试通知");
        n.setContent(req.content() != null ? req.content() : "这是一条测试通知");
        n.setTarget(req.target());
        notificationRepository.save(n);
        return Map.of("success", true);
    }

    private Long currentAgentId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedAgent agent) {
            return agent.agentId();
        }
        return null;
    }

    public record ReminderRequest(String name, String triggerType, String triggerConfig,
                                  String frequency, String channel, String messageTemplate,
                                  Boolean enabled) {}

    public record TestNotificationRequest(String type, String title, String content, String target) {}
}
