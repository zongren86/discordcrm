package com.discordadmin.controller;

import com.discordadmin.dto.UserStatsDtos.ActiveCustomerDto;
import com.discordadmin.entity.Agent;
import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.repository.*;
import com.discordadmin.security.SecurityUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final DiscordAccountRepository accountRepository;
    private final FriendRepository friendRepository;
    private final DiscordUserRepository userRepository;
    private final AgentRepository agentRepository;

    public StatsController(MessageRepository messageRepository,
                           ConversationRepository conversationRepository,
                           DiscordAccountRepository accountRepository,
                           FriendRepository friendRepository,
                           DiscordUserRepository userRepository,
                           AgentRepository agentRepository) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.accountRepository = accountRepository;
        this.friendRepository = friendRepository;
        this.userRepository = userRepository;
        this.agentRepository = agentRepository;
    }


    /** 仪表盘：筛选条件下拉数据 */
    @GetMapping("/dashboard-filters")
    public Map<String, Object> dashboardFilters() {
        Long merchantId = SecurityUtils.currentMerchantId();
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> accounts = new ArrayList<>();
        List<DiscordAccount> accountList = accountRepository.findByMerchantId(merchantId);
        for (DiscordAccount a : accountList) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("label", a.getName() != null ? a.getName() : a.getDiscordName());
            accounts.add(item);
        }
        result.put("accounts", accounts);

        List<Map<String, Object>> agents = new ArrayList<>();
        List<Agent> agentList = agentRepository.findByMerchantId(merchantId);
        for (Agent a : agentList) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("label", a.getDisplayName() != null ? a.getDisplayName() : a.getUsername());
            agents.add(item);
        }
        result.put("agents", agents);

        return result;
    }


    /** 仪表盘：核心统计数据 */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(
            @RequestParam(required = false) String datePreset,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String accountIds,
            @RequestParam(required = false) String agentIds) {

        Long merchantId = SecurityUtils.currentMerchantId();
        Instant[] range = resolveRange(datePreset, dateFrom, dateTo);
        List<Long> accountIdList = parseIds(accountIds);
        List<Long> agentIdList = parseIds(agentIds);

        Map<String, Object> result = new LinkedHashMap<>();

        // 漏斗状态
        Map<String, Long> funnelStats = new LinkedHashMap<>();
        funnelStats.put("prospect", conversationRepository.countByFilters(merchantId, accountIdList, agentIdList, Conversation.Stage.PROSPECT, range[0], range[1]));
        funnelStats.put("new", conversationRepository.countByFilters(merchantId, accountIdList, agentIdList, Conversation.Stage.NEW, range[0], range[1]));
        funnelStats.put("converted", conversationRepository.countByFilters(merchantId, accountIdList, agentIdList, Conversation.Stage.CONVERTED, range[0], range[1]));
        result.put("funnel", funnelStats);

        // 非漏斗状态
        Map<String, Long> nonFunnelStats = new LinkedHashMap<>();
        nonFunnelStats.put("churned", conversationRepository.countByFilters(merchantId, accountIdList, agentIdList, Conversation.Stage.CHURNED, range[0], range[1]));
        nonFunnelStats.put("archived", conversationRepository.countByFilters(merchantId, accountIdList, agentIdList, Conversation.Stage.ARCHIVED, range[0], range[1]));
        result.put("nonFunnel", nonFunnelStats);

        // 互动指标
        Map<String, Object> interactionStats = new LinkedHashMap<>();
        long sentCount = messageRepository.countByFilters(merchantId, accountIdList, agentIdList, range[0], range[1]);
        long visitedCustomers = conversationRepository.countVisitedCustomers(merchantId, accountIdList, agentIdList, range[0], range[1]);
        long activeCustomers = computeActiveCustomers(merchantId, accountIdList, agentIdList, range[0], range[1]);

        interactionStats.put("sentCount", sentCount);
        interactionStats.put("visitedCustomers", visitedCustomers);
        interactionStats.put("activeCustomers", activeCustomers);
        result.put("interaction", interactionStats);

        return result;
    }


    /** 仪表盘：趋势数据 */
    @GetMapping("/dashboard-trends")
    public Map<String, Object> dashboardTrends(
            @RequestParam(required = false) String datePreset,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String accountIds,
            @RequestParam(required = false) String agentIds) {

        Long merchantId = SecurityUtils.currentMerchantId();
        Instant[] range = resolveRange(datePreset, dateFrom, dateTo);
        List<Long> accountIdList = parseIds(accountIds);
        List<Long> agentIdList = parseIds(agentIds);

        Map<String, Object> result = new LinkedHashMap<>();

        int days = calculateDays(range[0], range[1]);
        List<String> dates = generateDateList(range[0], range[1]);

        // 初始化每日数据
        Map<String, int[]> dailyData = new LinkedHashMap<>();
        for (String d : dates) {
            dailyData.put(d, new int[5]); // [prospect, new, converted, archived, churned]
        }

        // 获取每日各阶段新增会话
        List<Object[]> dailyRows = conversationRepository.countDailyByFilters(merchantId, accountIdList, agentIdList, range[0], range[1]);
        for (Object[] row : dailyRows) {
            LocalDate day = toLocalDate(row[0]);
            Conversation.Stage stage = (Conversation.Stage) row[1];
            long count = row[2] instanceof Number n ? n.longValue() : 0L;
            String d = day.toString();
            int[] arr = dailyData.get(d);
            if (arr != null) {
                switch (stage) {
                    case PROSPECT -> arr[0] += (int) count;
                    case NEW -> arr[1] += (int) count;
                    case CONVERTED -> arr[2] += (int) count;
                    case ARCHIVED -> arr[3] += (int) count;
                    case CHURNED -> arr[4] += (int) count;
                    default -> {}
                }
            }
        }

        // 获客趋势 (prospect + new)
        List<Map<String, Object>> acquisitionTrend = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : dailyData.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", entry.getKey());
            item.put("prospect", entry.getValue()[0]);
            item.put("new", entry.getValue()[1]);
            acquisitionTrend.add(item);
        }
        result.put("acquisitionTrend", acquisitionTrend);

        // 转化漏斗 (converted)
        List<Map<String, Object>> conversionTrend = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : dailyData.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", entry.getKey());
            item.put("converted", entry.getValue()[2]);
            conversionTrend.add(item);
        }
        result.put("conversionTrend", conversionTrend);

        // 风险状态 (archived + churned)
        List<Map<String, Object>> riskTrend = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : dailyData.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", entry.getKey());
            item.put("archived", entry.getValue()[3]);
            item.put("churned", entry.getValue()[4]);
            riskTrend.add(item);
        }
        result.put("riskTrend", riskTrend);

        // 活跃客户趋势
        Map<String, Integer> activeCustomerMap = computeDailyActiveCustomers(merchantId, accountIdList, agentIdList, range[0], range[1]);
        List<Map<String, Object>> activeTrend = new ArrayList<>();
        for (String d : dates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", d);
            item.put("activeCustomers", activeCustomerMap.getOrDefault(d, 0));
            activeTrend.add(item);
        }
        result.put("activeCustomerTrend", activeTrend);

        return result;
    }


    /** 计算活跃客户数（当天发送消息>=3的客户数） */
    private long computeActiveCustomers(Long merchantId, List<Long> accountIds, List<Long> agentIds, Instant start, Instant end) {
        Map<String, Set<String>> dailyUserMessages = new LinkedHashMap<>();
        List<Object[]> rows = messageRepository.countDailyActiveCustomersRaw(merchantId, accountIds, agentIds, start, end);
        Map<String, Long> dailyCounts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            LocalDate day = toLocalDate(row[0]);
            Long count = (Long) row[1];
            dailyCounts.merge(day.toString(), count, Long::sum);
        }
        long total = 0;
        if (!dailyCounts.isEmpty()) {
            if (dailyCounts.size() == 1 || (end != null && start != null)) {
                total = dailyCounts.values().stream().mapToLong(Long::longValue).sum();
                long days = dailyCounts.size();
                total = days > 0 ? Math.round((double) total / days) : total;
            }
        }
        return total;
    }

    /** 计算每日活跃客户数（用于趋势图） */
    private Map<String, Integer> computeDailyActiveCustomers(Long merchantId, List<Long> accountIds, List<Long> agentIds, Instant start, Instant end) {
        Map<String, Integer> result = new LinkedHashMap<>();
        List<Object[]> rows = messageRepository.countDailyActiveCustomersRaw(merchantId, accountIds, agentIds, start, end);
        for (Object[] row : rows) {
            LocalDate day = toLocalDate(row[0]);
            Object val = row[1];
            int count = val instanceof Number n ? n.intValue() : 0;
            result.merge(day.toString(), count, Integer::sum);
        }
        return result;
    }


    private LocalDate toLocalDate(Object obj) {
        if (obj == null) return null;
        if (obj instanceof LocalDate ld) return ld;
        if (obj instanceof java.sql.Date sd) return sd.toLocalDate();
        if (obj instanceof java.util.Date ud) {
            return ud.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        throw new IllegalArgumentException("Cannot convert " + obj.getClass() + " to LocalDate");
    }

    /** 解析日期预设为时间范围 */
    private Instant[] resolveRange(String preset, String dateFrom, String dateTo) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        Instant start = null;
        Instant end = null;

        if (dateFrom != null && !dateFrom.isBlank()) {
            start = LocalDate.parse(dateFrom).atStartOfDay(ZoneId.systemDefault()).toInstant();
        }
        if (dateTo != null && !dateTo.isBlank()) {
            end = LocalDate.parse(dateTo).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        }

        if (start == null && end == null && preset != null) {
            switch (preset) {
                case "today" -> {
                    start = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
                    end = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                }
                case "week" -> {
                    start = today.minusDays(today.getDayOfWeek().getValue() - 1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                    end = start.plusSeconds(7 * 86400L);
                }
                case "month" -> {
                    start = today.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                    end = start.plusSeconds(32 * 86400L);
                }
                case "lastmonth" -> {
                    LocalDate firstDay = today.withDayOfMonth(1).minusMonths(1);
                    start = firstDay.atStartOfDay(ZoneId.systemDefault()).toInstant();
                    end = firstDay.plusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                }
                case "7d" -> {
                    start = today.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant();
                    end = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                }
                case "30d" -> {
                    start = today.minusDays(29).atStartOfDay(ZoneId.systemDefault()).toInstant();
                    end = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                }
                case "all" -> {}
                default -> {}
            }
        }

        return new Instant[]{start, end};
    }

    private int calculateDays(Instant start, Instant end) {
        if (start == null || end == null) return 30;
        long days = (end.getEpochSecond() - start.getEpochSecond()) / 86400;
        return (int) Math.max(days, 1);
    }

    private List<String> generateDateList(Instant start, Instant end) {
        List<String> dates = new ArrayList<>();
        if (start == null || end == null) {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            for (int i = 29; i >= 0; i--) {
                dates.add(today.minusDays(i).toString());
            }
            return dates;
        }
        LocalDate startDate = LocalDate.ofInstant(start, ZoneId.systemDefault());
        LocalDate endDate = LocalDate.ofInstant(end, ZoneId.systemDefault());
        for (LocalDate d = startDate; d.isBefore(endDate); d = d.plusDays(1)) {
            dates.add(d.toString());
        }
        return dates;
    }

    private List<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) return null;
        List<Long> result = new ArrayList<>();
        for (String s : ids.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) {
                try {
                    result.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {}
            }
        }
        return result.isEmpty() ? null : result;
    }


    // ========== 保留原有方法 ==========

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

        Instant[] range = parseRangeOld(dateFrom, dateTo);
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

    @GetMapping("/active-customers")
    public List<ActiveCustomerDto> activeCustomersToday(@RequestParam(required = false) String dateFrom) {
        Instant start = (dateFrom != null && !dateFrom.isBlank())
                ? LocalDate.parse(dateFrom).atStartOfDay(ZoneId.systemDefault()).toInstant()
                : LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Long merchantId = SecurityUtils.currentMerchantId();
        return messageRepository.findActiveCustomersSinceAndMerchant(start, merchantId);
    }

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

    @GetMapping("/by-agent")
    public List<Map<String, Object>> statsByAgent(@RequestParam(required = false) String dateFrom,
                                                    @RequestParam(required = false) String dateTo) {
        Long merchantId = SecurityUtils.currentMerchantId();
        Instant[] range = parseRangeOld(dateFrom, dateTo);
        List<Map<String, Object>> result = new ArrayList<>();
        List<Object[]> convByAgent = conversationRepository.countByAssignedAgentAndMerchant(merchantId);
        for (Object[] row : convByAgent) {
            Map<String, Object> item = new LinkedHashMap<>();
            Object agentObj = row[0];
            if (agentObj instanceof com.discordadmin.entity.Agent agent) {
                item.put("agentId", agent.getId());
                item.put("agentName", agent.getDisplayName() != null ? agent.getDisplayName() : agent.getUsername());
                item.put("accountType", agent.getAccountType() != null ? agent.getAccountType() : 1);
                item.put("roleLabel", agent.isAdmin() ? "管理员" : "普通账号");
                item.put("conversationCount", row[1]);
                long msgCount;
                if (range != null) {
                    msgCount = messageRepository.countByAgentAndDateRange(agent.getId(), range[0], range[1]);
                } else {
                    msgCount = messageRepository.countByAgent(agent.getId());
                }
                item.put("messageCount", msgCount);
            } else if (agentObj == null) {
                item.put("agentId", null);
                item.put("agentName", "未分配");
                item.put("accountType", null);
                item.put("roleLabel", "-");
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

    private Instant[] parseRangeOld(String dateFrom, String dateTo) {
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

