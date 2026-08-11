package com.discordadmin.controller;

import com.discordadmin.dto.UserStatsDtos.ActiveCustomerDto;
import com.discordadmin.entity.Conversation;
import com.discordadmin.repository.ConversationRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.DiscordUserRepository;
import com.discordadmin.repository.FriendRepository;
import com.discordadmin.repository.MessageRepository;
import com.discordadmin.security.SecurityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final DiscordAccountRepository accountRepository;
    private final FriendRepository friendRepository;
    private final DiscordUserRepository userRepository;

    public StatsController(MessageRepository messageRepository,
                           ConversationRepository conversationRepository,
                           DiscordAccountRepository accountRepository,
                           FriendRepository friendRepository,
                           DiscordUserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.accountRepository = accountRepository;
        this.friendRepository = friendRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public Map<String, Object> stats(@RequestParam(required = false) String dateFrom,
                                     @RequestParam(required = false) String dateTo) {
        Map<String, Object> result = new LinkedHashMap<>();
        Long merchantId = SecurityUtils.currentMerchantId();

        result.put("totalMessages", messageRepository.countByMerchantId(merchantId));
        result.put("totalConversations", conversationRepository.countByMerchantId(merchantId));
        result.put("totalFriends", friendRepository.countByMerchantId(merchantId));
        result.put("totalAccounts", accountRepository.countByMerchantId(merchantId));
        result.put("totalCustomers", userRepository.countByMerchantId(merchantId));

        Instant[] range = parseRange(dateFrom, dateTo);
        if (range != null) {
            result.put("messagesInRange", messageRepository.countByCreatedAtBetweenAndMerchant(range[0], range[1], merchantId));
        }
        return result;
    }

    @GetMapping("/stage-distribution")
    public List<Map<String, Object>> stageDistribution() {
        Long merchantId = SecurityUtils.currentMerchantId();
        List<Object[]> rows = conversationRepository.countByStageAndMerchantId(merchantId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            Object stageObj = row[0];
            if (stageObj instanceof Conversation.Stage s) {
                item.put("stage", s.name());
                item.put("count", row[1]);
            } else if (stageObj instanceof String s) {
                item.put("stage", s);
                item.put("count", row[1]);
            }
            result.add(item);
        }
        return result;
    }

    /** 活跃客户：今日或指定日期范围 */
    @GetMapping("/active-customers")
    public List<ActiveCustomerDto> activeCustomersToday(@RequestParam(required = false) String dateFrom) {
        Instant start = (dateFrom != null && !dateFrom.isBlank())
                ? LocalDate.parse(dateFrom).atStartOfDay(ZoneId.systemDefault()).toInstant()
                : LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Long merchantId = SecurityUtils.currentMerchantId();
        return messageRepository.findActiveCustomersSinceAndMerchant(start, merchantId);
    }

    /** 每日消息趋势：返回最近 N 天的消息条数 */
    @GetMapping("/trend")
    public List<Map<String, Object>> trend(@RequestParam(defaultValue = "7") int days) {
        Long merchantId = SecurityUtils.currentMerchantId();

        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        for (int i = days - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            Instant start = day.atStartOfDay(ZoneId.systemDefault()).toInstant();
            Instant end = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

            long msgCount = messageRepository.countByCreatedAtBetweenAndMerchant(start, end, merchantId);
            long convCount = conversationRepository.countByLastMessageAtBetweenAndMerchant(start, end, merchantId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", day.toString());
            item.put("messages", msgCount);
            item.put("conversations", convCount);
            result.add(item);
        }
        return result;
    }

    /** 按客服统计会话和消息数 */
    @GetMapping("/by-agent")
    public List<Map<String, Object>> statsByAgent(@RequestParam(required = false) String dateFrom,
                                                    @RequestParam(required = false) String dateTo) {
        Long merchantId = SecurityUtils.currentMerchantId();
        Instant[] range = parseRange(dateFrom, dateTo);
        List<Map<String, Object>> result = new ArrayList<>();

        List<Object[]> convByAgent = conversationRepository.countByAssignedAgentAndMerchant(merchantId);
        for (Object[] row : convByAgent) {
            Map<String, Object> item = new LinkedHashMap<>();
            Object agentObj = row[0];
            if (agentObj instanceof com.discordadmin.entity.Agent agent) {
                item.put("agentId", agent.getId());
                item.put("agentName", agent.getDisplayName() != null ? agent.getDisplayName() : agent.getUsername());
                item.put("role", agent.getRole().name());
                item.put("conversationCount", row[1]);

                long msgCount = 0;
                if (range != null) {
                    msgCount = messageRepository.countByAgentAndDateRange(agent.getId(), range[0], range[1]);
                } else {
                    msgCount = messageRepository.countByAgent(agent.getId());
                }
                item.put("messageCount", msgCount);
            } else if (agentObj == null) {
                item.put("agentId", null);
                item.put("agentName", "未分配");
                item.put("role", "-");
                item.put("conversationCount", row[1]);
                item.put("messageCount", 0);
            }
            result.add(item);
        }

        result.sort((a, b) -> Long.compare(
                ((Number) b.get("conversationCount")).longValue(),
                ((Number) a.get("conversationCount")).longValue()));
        return result;
    }

    /** 销售漏斗转化率 */
    @GetMapping("/conversion-rate")
    public Map<String, Object> conversionRate() {
        Long merchantId = SecurityUtils.currentMerchantId();
        List<Object[]> rows = conversationRepository.countByStageAndMerchantId(merchantId);
        Map<String, Long> stageCounts = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : rows) {
            String stage = null;
            Object stageObj = row[0];
            if (stageObj instanceof Conversation.Stage s) {
                stage = s.name();
            } else if (stageObj instanceof String s) {
                stage = s;
            }
            if (stage == null || stage.isBlank()) {
                stage = "UNKNOWN";
            }
            long count = row[1] instanceof Number n ? n.longValue() : 0L;
            stageCounts.put(stage, count);
            total += count;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        Map<String, Double> rates = new LinkedHashMap<>();
        Conversation.Stage[] stages = Conversation.Stage.values();
        for (Conversation.Stage stage : stages) {
            long count = stageCounts.getOrDefault(stage.name(), 0L);
            double rate = total > 0 ? (count * 100.0 / total) : 0;
            rates.put(stage.name(), Math.round(rate * 100.0) / 100.0);
        }
        result.put("rates", rates);
        result.put("stageCounts", stageCounts);
        return result;
    }

    /** 客户活跃度统计 */
    @GetMapping("/customer-activity")
    public Map<String, Object> customerActivity(@RequestParam(required = false) String dateFrom) {
        Instant start = (dateFrom != null && !dateFrom.isBlank())
                ? LocalDate.parse(dateFrom).atStartOfDay(ZoneId.systemDefault()).toInstant()
                : LocalDate.now(ZoneId.systemDefault()).minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant();

        Long merchantId = SecurityUtils.currentMerchantId();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeCustomers", messageRepository.countActiveCustomersSinceAndMerchant(start, merchantId));
        result.put("newConversations", conversationRepository.countByCreatedAtAfterAndMerchant(start, merchantId));
        result.put("totalMessagesInRange", messageRepository.countByCreatedAtAfterAndMerchant(start, merchantId));

        return result;
    }

    private Instant[] parseRange(String dateFrom, String dateTo) {
        if ((dateFrom == null || dateFrom.isBlank()) && (dateTo == null || dateTo.isBlank())) {
            return null;
        }
        Instant from = (dateFrom != null && !dateFrom.isBlank())
                ? LocalDate.parse(dateFrom).atStartOfDay(ZoneId.systemDefault()).toInstant()
                : Instant.now().minusSeconds(86400L * 30);
        Instant to = (dateTo != null && !dateTo.isBlank())
                ? LocalDate.parse(dateTo).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
                : Instant.now();
        return new Instant[]{from, to};
    }
}
