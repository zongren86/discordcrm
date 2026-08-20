package com.discordadmin.controller;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordAccountNumber;
import com.discordadmin.entity.Friend;
import com.discordadmin.entity.GuildMember;
import com.discordadmin.entity.GuildServer;
import com.discordadmin.repository.AgentAccountNumberRelRepository;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.ConversationRepository;
import com.discordadmin.repository.DiscordAccountNumberRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.FriendRepository;
import com.discordadmin.repository.GuildMemberRepository;
import com.discordadmin.repository.GuildServerRepository;
import com.discordadmin.security.SecurityUtils;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Optional;

@RestController
@RequestMapping("/api/guild-members")
public class GuildMembersController {

    private static final Logger log = LoggerFactory.getLogger(GuildMembersController.class);

    private final GuildMemberRepository guildMemberRepository;
    private final GuildServerRepository guildServerRepository;
    private final DiscordAccountRepository discordAccountRepository;
    private final FriendRepository friendRepository;
    private final ConversationRepository conversationRepository;
    private final AgentRepository agentRepository;
    private final AgentAccountNumberRelRepository relRepository;
    private final DiscordAccountNumberRepository accountNumberRepository;

    public GuildMembersController(GuildMemberRepository guildMemberRepository,
                                  GuildServerRepository guildServerRepository,
                                  DiscordAccountRepository discordAccountRepository,
                                  FriendRepository friendRepository,
                                  ConversationRepository conversationRepository,
                                  AgentRepository agentRepository,
                                  AgentAccountNumberRelRepository relRepository,
                                  DiscordAccountNumberRepository accountNumberRepository) {
        this.guildMemberRepository = guildMemberRepository;
        this.guildServerRepository = guildServerRepository;
        this.discordAccountRepository = discordAccountRepository;
        this.friendRepository = friendRepository;
        this.conversationRepository = conversationRepository;
        this.agentRepository = agentRepository;
        this.relRepository = relRepository;
        this.accountNumberRepository = accountNumberRepository;
    }

    @GetMapping
    public PageResponse<MemberDTO> listMembers(
            @RequestParam(required = false) Long guildServerId,
            @RequestParam(required = false) Long discordAccountId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String discordStatus,
            @RequestParam(required = false) Integer friendStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fetchDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fetchDateTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant passDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant passDateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long merchantId = SecurityUtils.currentMerchantId();
        String role = SecurityUtils.currentRole();
        Long currentAgentId = SecurityUtils.currentAgentId();

        List<GuildMember> members = queryMembers(guildServerId, keyword);

        Map<Long, GuildServer> serverMap = batchFetchServers(members);

        members = filterByDiscordAccount(members, serverMap, discordAccountId);

        Map<Long, DiscordAccount> accountMap = batchFetchAccounts(serverMap);

        Map<Long, Friend> friendMap = batchFetchFriends(members);

        Map<String, Map<Long, Conversation>> conversationMap = batchFetchConversations(members, friendMap, serverMap);

        List<MemberDTO> dtos = buildDTOs(members, serverMap, accountMap, friendMap, conversationMap);

        // 权限过滤：普通用户只能看到自己名下会话的成员
        dtos = filterDtosByAgentAccess(dtos, conversationMap, currentAgentId, role);

        dtos = applyFilters(dtos, discordStatus, friendStatus, fetchDateFrom, fetchDateTo, passDateFrom, passDateTo);

        return paginate(dtos, page, size);
    }

    @GetMapping("/servers")
    public List<Map<String, Object>> listServers() {
        Long merchantId = SecurityUtils.currentMerchantId();
        String role = SecurityUtils.currentRole();
        Long currentAgentId = SecurityUtils.currentAgentId();

        List<GuildServer> servers;

        // 普通用户：只能看到分配给自己账号的服务器
        if (!"PLATFORM_ADMIN".equals(role) && !"MERCHANT_ADMIN".equals(role) && currentAgentId != null) {
            Set<Long> assignedAccountIds = getAssignedAccountIds(currentAgentId);
            if (assignedAccountIds.isEmpty()) {
                return List.of();
            }
            List<Long> accountIdList = new ArrayList<>(assignedAccountIds);
            if (merchantId != null) {
                servers = guildServerRepository.findByMerchantIdAndDiscordAccountIdIn(merchantId, accountIdList);
            } else {
                servers = guildServerRepository.findByDiscordAccountIdIn(accountIdList);
            }
        } else if (merchantId != null) {
            servers = guildServerRepository.findByMerchantId(merchantId);
        } else {
            servers = guildServerRepository.findAll();
        }
        return servers.stream().map(s -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", s.getId());
            map.put("name", s.getName());
            return map;
        }).collect(Collectors.toList());
    }

    @GetMapping("/accounts")
    public List<Map<String, Object>> listAccounts() {
        Long merchantId = SecurityUtils.currentMerchantId();
        String role = SecurityUtils.currentRole();
        Long currentAgentId = SecurityUtils.currentAgentId();

        List<DiscordAccount> accounts;

        // 普通用户：只能看到自己分配的账号
        if (!"PLATFORM_ADMIN".equals(role) && !"MERCHANT_ADMIN".equals(role) && currentAgentId != null) {
            Set<Long> assignedAccountIds = getAssignedAccountIds(currentAgentId);
            if (assignedAccountIds.isEmpty()) {
                return List.of();
            }
            accounts = discordAccountRepository.findAllById(new ArrayList<>(assignedAccountIds));
        } else if (merchantId != null) {
            accounts = discordAccountRepository.findByMerchantId(merchantId);
        } else {
            accounts = discordAccountRepository.findAll();
        }
        return accounts.stream().map(a -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", a.getId());
            map.put("name", a.getName());
            return map;
        }).sorted(Comparator.comparing(m -> (String) m.getOrDefault("name", "")))
          .collect(Collectors.toList());
    }

    /**
     * 权限过滤：普通用户只能看到自己名下会话的成员 或 分配账号下的成员
     */
    private List<MemberDTO> filterDtosByAgentAccess(List<MemberDTO> dtos,
                                                     Map<String, Map<Long, Conversation>> conversationMap,
                                                     Long currentAgentId, String role) {
        if (dtos.isEmpty()) return dtos;

        // 平台管理员和商户管理员可见全部
        if ("PLATFORM_ADMIN".equals(role) || "MERCHANT_ADMIN".equals(role)) {
            return dtos;
        }

        // 普通用户：只有对应会话的 ownerAgentId = currentAgentId 或 账号在分配列表中 才可见
        if (currentAgentId == null) return List.of();

        Set<Long> assignedAccountIds = getAssignedAccountIds(currentAgentId);

        return dtos.stream()
                .filter(d -> {
                    // 查找该成员对应的会话
                    Map<Long, Conversation> accountConvs = conversationMap.get(d.getUserId());
                    if (accountConvs == null || accountConvs.isEmpty()) {
                        // 没有会话的成员：只有待添加状态可见（未分配前不限制）
                        return d.getFriendStatus() != null && d.getFriendStatus() <= 1;
                    }
                    // 检查是否有任何一个会话属于当前用户（ownerAgentId=自己 或 账号在分配列表中）
                    return accountConvs.values().stream()
                            .anyMatch(c -> currentAgentId.equals(c.getOwnerAgentId())
                                    || (c.getDiscordAccount() != null && assignedAccountIds.contains(c.getDiscordAccount().getId())));
                })
                .collect(Collectors.toList());
    }

    private List<GuildMember> queryMembers(Long guildServerId, String keyword) {
        List<GuildMember> members;

        if (guildServerId != null && keyword != null && !keyword.isBlank()) {
            members = guildMemberRepository.searchByGuildServerId(guildServerId, keyword,
                    org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)).getContent();
        } else if (guildServerId != null) {
            members = guildMemberRepository.findByGuildServerId(guildServerId);
        } else {
            members = guildMemberRepository.findAll();
            if (keyword != null && !keyword.isBlank()) {
                String kw = keyword.toLowerCase();
                members = members.stream()
                        .filter(m -> containsKeyword(m, kw))
                        .collect(Collectors.toList());
            }
        }

        return members;
    }

    private boolean containsKeyword(GuildMember m, String kw) {
        return (m.getUsername() != null && m.getUsername().toLowerCase().contains(kw))
                || (m.getNick() != null && m.getNick().toLowerCase().contains(kw))
                || (m.getGlobalName() != null && m.getGlobalName().toLowerCase().contains(kw))
                || (m.getDisplayName() != null && m.getDisplayName().toLowerCase().contains(kw))
                || (m.getUserId() != null && m.getUserId().toLowerCase().contains(kw));
    }

    private Map<Long, GuildServer> batchFetchServers(List<GuildMember> members) {
        Set<Long> serverIds = members.stream()
                .map(GuildMember::getGuildServerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, GuildServer> serverMap = new HashMap<>();
        if (!serverIds.isEmpty()) {
            guildServerRepository.findAllById(serverIds).forEach(s -> serverMap.put(s.getId(), s));
        }
        return serverMap;
    }

    private List<GuildMember> filterByDiscordAccount(List<GuildMember> members,
                                                     Map<Long, GuildServer> serverMap,
                                                     Long discordAccountId) {
        if (discordAccountId == null) {
            return members;
        }
        return members.stream()
                .filter(m -> {
                    GuildServer server = serverMap.get(m.getGuildServerId());
                    return server != null && discordAccountId.equals(server.getDiscordAccountId());
                })
                .collect(Collectors.toList());
    }

    private Map<Long, DiscordAccount> batchFetchAccounts(Map<Long, GuildServer> serverMap) {
        Set<Long> accountIds = serverMap.values().stream()
                .map(GuildServer::getDiscordAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, DiscordAccount> accountMap = new HashMap<>();
        if (!accountIds.isEmpty()) {
            discordAccountRepository.findByIdIn(new ArrayList<>(accountIds))
                    .forEach(a -> accountMap.put(a.getId(), a));
        }
        return accountMap;
    }

    private Map<Long, Friend> batchFetchFriends(List<GuildMember> members) {
        Map<Long, Friend> friendMap = new HashMap<>();

        Map<Long, List<GuildMember>> byServer = members.stream()
                .filter(m -> m.getGuildServerId() != null && m.getUserId() != null)
                .collect(Collectors.groupingBy(GuildMember::getGuildServerId));

        for (Map.Entry<Long, List<GuildMember>> entry : byServer.entrySet()) {
            Long serverId = entry.getKey();
            List<String> userIds = entry.getValue().stream()
                    .map(GuildMember::getUserId)
                    .distinct()
                    .collect(Collectors.toList());

            List<Friend> friends = friendRepository.findByGuildServerIdAndFriendDiscordUserIdIn(serverId, userIds);
            Map<String, Friend> serverFriendMap = new HashMap<>();
            for (Friend f : friends) {
                serverFriendMap.put(f.getFriendDiscordUserId(), f);
            }
            for (GuildMember m : entry.getValue()) {
                Friend f = serverFriendMap.get(m.getUserId());
                if (f != null) {
                    friendMap.put(m.getId(), f);
                }
            }
        }

        Set<String> matchedUserIds = friendMap.values().stream()
                .map(Friend::getFriendDiscordUserId)
                .collect(Collectors.toSet());

        Set<String> allUserIds = members.stream()
                .map(GuildMember::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> remainingUserIds = new HashSet<>(allUserIds);
        remainingUserIds.removeAll(matchedUserIds);

        if (!remainingUserIds.isEmpty()) {
            List<Friend> fallbackFriends = friendRepository.findByFriendDiscordUserIdIn(new ArrayList<>(remainingUserIds));
            Map<String, Friend> fallbackMap = new HashMap<>();
            for (Friend f : fallbackFriends) {
                fallbackMap.put(f.getFriendDiscordUserId(), f);
            }
            for (GuildMember m : members) {
                if (!friendMap.containsKey(m.getId())) {
                    Friend f = fallbackMap.get(m.getUserId());
                    if (f != null) {
                        friendMap.put(m.getId(), f);
                    }
                }
            }
        }

        return friendMap;
    }

    private Map<String, Map<Long, Conversation>> batchFetchConversations(List<GuildMember> members,
                                                                         Map<Long, Friend> friendMap,
                                                                         Map<Long, GuildServer> serverMap) {
        Map<String, Map<Long, Conversation>> conversationMap = new HashMap<>();

        // 收集所有需要查询的用户ID和账号ID
        Set<String> userIdsToQuery = new HashSet<>();
        Set<Long> accountIdsToQuery = new HashSet<>();

        for (GuildMember m : members) {
            Friend friend = friendMap.get(m.getId());
            if (friend != null && friend.getStatus() == Friend.FriendStatus.ACCEPTED && m.getUserId() != null) {
                GuildServer server = serverMap.get(m.getGuildServerId());
                if (server != null && server.getDiscordAccountId() != null) {
                    userIdsToQuery.add(m.getUserId());
                    accountIdsToQuery.add(server.getDiscordAccountId());
                }
            }
        }

        if (userIdsToQuery.isEmpty() || accountIdsToQuery.isEmpty()) {
            return conversationMap;
        }

        // 批量查询所有匹配的会话
        List<Conversation> allConvs = conversationRepository.findByDiscordUserIdsInAndAccountIdsIn(
                new ArrayList<>(userIdsToQuery), new ArrayList<>(accountIdsToQuery));

        // 按userId和accountId分组，取每组最后一条消息的会话
        Map<String, List<Conversation>> convsByUserId = allConvs.stream()
                .filter(c -> c.getDiscordUser() != null && c.getDiscordAccount() != null)
                .collect(Collectors.groupingBy(c -> c.getDiscordUser().getDiscordUserId()));

        for (Map.Entry<String, List<Conversation>> entry : convsByUserId.entrySet()) {
            String userId = entry.getKey();
            Map<Long, Conversation> accountConvMap = new HashMap<>();

            // 按accountId分组，每组取最后消息时间最大的
            Map<Long, List<Conversation>> byAccount = entry.getValue().stream()
                    .collect(Collectors.groupingBy(c -> c.getDiscordAccount().getId()));

            for (Map.Entry<Long, List<Conversation>> accEntry : byAccount.entrySet()) {
                List<Conversation> convs = accEntry.getValue();
                convs.sort(Comparator.comparing(Conversation::getLastMessageAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
                accountConvMap.put(accEntry.getKey(), convs.get(0));
            }

            if (!accountConvMap.isEmpty()) {
                conversationMap.put(userId, accountConvMap);
            }
        }

        return conversationMap;
    }

    private List<MemberDTO> buildDTOs(List<GuildMember> members,
                                      Map<Long, GuildServer> serverMap,
                                      Map<Long, DiscordAccount> accountMap,
                                      Map<Long, Friend> friendMap,
                                      Map<String, Map<Long, Conversation>> conversationMap) {
        List<MemberDTO> dtos = new ArrayList<>();

        // 批量获取分配给成员的Discord账号（用于添加账号列）
        Set<Long> assignedAccountIds = members.stream()
                .map(GuildMember::getDiscordAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, DiscordAccount> assignedAccountMap = new HashMap<>();
        if (!assignedAccountIds.isEmpty()) {
            discordAccountRepository.findByIdIn(new ArrayList<>(assignedAccountIds))
                    .forEach(a -> assignedAccountMap.put(a.getId(), a));
        }

        for (GuildMember m : members) {
            GuildServer server = serverMap.get(m.getGuildServerId());
            DiscordAccount account = server != null ? accountMap.get(server.getDiscordAccountId()) : null;
            Friend friend = friendMap.get(m.getId());

            // 好友添加状态
            Integer friendStatus = m.getFriendStatus();
            String friendStatusText = getFriendStatusText(friendStatus);
            
            // Discord在线状态
            String discordStatus = m.getDiscordStatus();

            // 获取添加好友时使用的账号名称
            String assignedAccountName = null;
            if (m.getDiscordAccountId() != null) {
                DiscordAccount assignedAccount = assignedAccountMap.get(m.getDiscordAccountId());
                if (assignedAccount != null) {
                    assignedAccountName = assignedAccount.getName();
                }
            }

            MemberDTO dto = new MemberDTO();
            dto.setId(m.getId());
            dto.setGuildServerId(m.getGuildServerId());
            dto.setGuildServerName(server != null ? server.getName() : null);
            dto.setDiscordAccountId(server != null ? server.getDiscordAccountId() : null);
            dto.setDiscordAccountName(account != null ? account.getName() : null);
            dto.setUserId(m.getUserId());
            dto.setUsername(m.getUsername());
            dto.setNick(m.getNick());
            dto.setDisplayName(m.getDisplayName());
            dto.setGlobalName(m.getGlobalName());
            dto.setDiscordStatus(discordStatus);
            dto.setFriendStatus(friendStatus);
            dto.setFriendStatusText(friendStatusText);
            dto.setLastError(m.getLastError());
            dto.setAssignedAccountName(assignedAccountName);
            dto.setPassDate(m.getFinishedAt() != null ? m.getFinishedAt() : 
                (friend != null ? friend.getCreatedAt() : null));
            dto.setJoinedAt(m.getJoinedAt());
            dto.setLastFetchedAt(m.getLastFetchedAt());

            dtos.add(dto);
        }

        return dtos;
    }

    private String getFriendStatusText(Integer status) {
        if (status == null) return "待添加";
        return switch (status) {
            case 0 -> "待添加";
            case 1 -> "已分配";
            case 2 -> "添加成功";
            case 3 -> "添加失败";
            default -> "未知";
        };
    }

    private List<MemberDTO> applyFilters(List<MemberDTO> dtos,
                                         String discordStatus,
                                         Integer friendStatus,
                                         Instant fetchDateFrom,
                                         Instant fetchDateTo,
                                         Instant passDateFrom,
                                         Instant passDateTo) {
        return dtos.stream()
                .filter(d -> discordStatus == null || discordStatus.isBlank() || 
                        discordStatus.equalsIgnoreCase(d.getDiscordStatus()))
                .filter(d -> friendStatus == null || friendStatus.equals(d.getFriendStatus()))
                .filter(d -> fetchDateFrom == null ||
                        (d.getLastFetchedAt() != null && !d.getLastFetchedAt().isBefore(fetchDateFrom)))
                .filter(d -> fetchDateTo == null ||
                        (d.getLastFetchedAt() != null && !d.getLastFetchedAt().isAfter(fetchDateTo)))
                .filter(d -> passDateFrom == null ||
                        (d.getPassDate() != null && !d.getPassDate().isBefore(passDateFrom)))
                .filter(d -> passDateTo == null ||
                        (d.getPassDate() != null && !d.getPassDate().isAfter(passDateTo)))
                .collect(Collectors.toList());
    }

    private PageResponse<MemberDTO> paginate(List<MemberDTO> dtos, int page, int size) {
        int total = dtos.size();
        int totalPages = (int) Math.ceil((double) total / size);
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, total);
        List<MemberDTO> content = fromIndex >= total ? new ArrayList<>() : dtos.subList(fromIndex, toIndex);

        PageResponse<MemberDTO> response = new PageResponse<>();
        response.setContent(content);
        response.setTotalElements(total);
        response.setTotalPages(totalPages);
        response.setCurrentPage(page);
        response.setSize(size);
        return response;
    }

    @Data
    public static class MemberDTO {
        Long id;
        Long guildServerId;
        String guildServerName;
        Long discordAccountId;
        String discordAccountName;
        String userId;
        String username;
        String nick;
        String displayName;
        String globalName;
        String discordStatus;
        Integer friendStatus;
        String friendStatusText;
        String lastError;
        String assignedAccountName;
        Instant passDate;
        Instant joinedAt;
        Instant lastFetchedAt;
    }

    @Data
    public static class PageResponse<T> {
        List<T> content;
        long totalElements;
        int totalPages;
        int currentPage;
        int size;
    }

    /** 获取当前用户有权限的账号ID列表（用于权限过滤） */
    private Set<Long> getAssignedAccountIds(Long currentAgentId) {
        Set<Long> assignedAccountIds = new HashSet<>();

        Optional<Agent> agentOpt = agentRepository.findById(currentAgentId);
        if (agentOpt.isEmpty()) {
            return assignedAccountIds;
        }

        Agent agent = agentOpt.get();

        // 1. 直接关联的账号（agent_discord_accounts）
        assignedAccountIds.addAll(
                agent.getDiscordAccounts().stream()
                        .map(DiscordAccount::getId)
                        .collect(Collectors.toSet()));

        // 2. 通过编号链路关联的账号（AgentAccountNumberRel → DiscordAccountNumber → DiscordAccount）
        List<Long> assignedNumberIds = relRepository.findAccountNumberIdsByAgentId(currentAgentId);
        if (!assignedNumberIds.isEmpty()) {
            List<DiscordAccountNumber> numbers = accountNumberRepository.findByIdIn(assignedNumberIds);
            for (DiscordAccountNumber num : numbers) {
                if (num.getDiscordAccountId() != null) {
                    assignedAccountIds.add(num.getDiscordAccountId());
                }
            }
        }

        return assignedAccountIds;
    }
}