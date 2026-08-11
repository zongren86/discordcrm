package com.discordadmin.controller;

import com.discordadmin.entity.DiscordUser;
import com.discordadmin.entity.Friend;
import com.discordadmin.repository.DiscordUserRepository;
import com.discordadmin.repository.FriendRepository;
import com.discordadmin.security.SecurityUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/discord-users")
public class DiscordUserController {

    private final DiscordUserRepository userRepository;
    private final FriendRepository friendRepository;

    public DiscordUserController(DiscordUserRepository userRepository,
                                 FriendRepository friendRepository) {
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
    }

    /** 获取用户资料（客户侧栏数据） */
    @GetMapping("/{discordUserId}")
    public UserProfileDto getUserProfile(@PathVariable String discordUserId) {
        DiscordUser user = userRepository.findByDiscordUserId(discordUserId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        String serverName = null;
        List<Friend> friends = friendRepository.findByFriendDiscordUserIdIn(List.of(discordUserId));
        if (!friends.isEmpty()) {
            Friend friend = friends.get(0);
            if (friend.getServerMatched() != null && friend.getServerMatched() && friend.getServerName() != null) {
                serverName = friend.getServerName();
            }
        }

        return UserProfileDto.from(user, serverName);
    }

    /** 更新用户备注/标签 */
    @PutMapping("/{discordUserId}")
    public UserProfileDto updateUser(@PathVariable String discordUserId, @RequestBody UpdateUserRequest request) {
        DiscordUser user = userRepository.findByDiscordUserId(discordUserId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (request.notes() != null) user.setNotes(request.notes());
        if (request.tags() != null) user.setTags(request.tags());
        if (request.status() != null) user.setStatus(DiscordUser.UserStatus.valueOf(request.status()));
        return UserProfileDto.from(userRepository.save(user));
    }

    /** 搜索用户（按标签/备注/用户名） */
    @GetMapping("/search")
    public List<UserProfileDto> searchUsers(@RequestParam String keyword) {
        String kw = keyword.trim().toLowerCase();
        return userRepository.findAll().stream()
                .filter(u -> {
                    String notes = u.getNotes() != null ? u.getNotes().toLowerCase() : "";
                    String tags = u.getTags() != null ? u.getTags().toLowerCase() : "";
                    String username = u.getUsername() != null ? u.getUsername().toLowerCase() : "";
                    String globalName = u.getGlobalName() != null ? u.getGlobalName().toLowerCase() : "";
                    String uid = u.getDiscordUserId() != null ? u.getDiscordUserId().toLowerCase() : "";
                    return notes.contains(kw) || tags.contains(kw)
                            || username.contains(kw) || globalName.contains(kw) || uid.contains(kw);
                })
                .map(UserProfileDto::from)
                .toList();
    }

    public record UpdateUserRequest(String notes, String tags, String status) {}

    public record UserProfileDto(
            Long id, String discordUserId, String username, String globalName,
            String avatarUrl, String notes, String tags, String status,
            String firstSeenAt, String lastActiveAt, String serverName
    ) {
        public static UserProfileDto from(DiscordUser u) {
            return new UserProfileDto(
                    u.getId(), u.getDiscordUserId(), u.getUsername(), u.getGlobalName(),
                    u.getAvatarUrl(), u.getNotes(), u.getTags(),
                    u.getStatus() != null ? u.getStatus().name() : "NORMAL",
                    u.getFirstSeenAt() != null ? u.getFirstSeenAt().toString() : null,
                    u.getLastActiveAt() != null ? u.getLastActiveAt().toString() : null,
                    null
            );
        }

        public static UserProfileDto from(DiscordUser u, String serverName) {
            return new UserProfileDto(
                    u.getId(), u.getDiscordUserId(), u.getUsername(), u.getGlobalName(),
                    u.getAvatarUrl(), u.getNotes(), u.getTags(),
                    u.getStatus() != null ? u.getStatus().name() : "NORMAL",
                    u.getFirstSeenAt() != null ? u.getFirstSeenAt().toString() : null,
                    u.getLastActiveAt() != null ? u.getLastActiveAt().toString() : null,
                    serverName
            );
        }
    }
}
