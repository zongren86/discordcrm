package com.discordadmin.dto;

import com.discordadmin.dto.MessageDtos.MessageDto;
import com.discordadmin.entity.Message;

import java.util.List;

/** 消息分页返回体（Slice 风格） */
public class MessagePageDto {
    /** 按 created_at 升序排列的一页消息（前端从旧→新直接渲染） */
    public final List<MessageDto> messages;
    /** 还有更早的历史消息可加载（前端用 hasMore 也行，保持命名兼容） */
    public final boolean hasMore;
    /** 为了兼容前端 hasMore 字段判断，冗余暴露 hasOlder */
    public boolean isHasOlder() { return hasMore; }
    /** 前端下次请求"加载更早"时要传的 beforeId（=当前页最旧那条消息的 id） */
    public final Long oldestId;
    /** 对应 beforeId 的 created_at，游标用 */
    public final String oldestCreatedAt;

    public MessagePageDto(List<MessageDto> messages, boolean hasMore, Long oldestId, String oldestCreatedAt) {
        this.messages = messages;
        this.hasMore = hasMore;
        this.oldestId = oldestId;
        this.oldestCreatedAt = oldestCreatedAt;
    }

    public static MessagePageDto fromEntities(List<Message> list, boolean hasMore) {
        List<MessageDto> dtos = list.stream().map(MessageDto::from).toList();
        Long oldestId = null;
        String oldestCreatedAt = null;
        if (!list.isEmpty()) {
            Message first = list.get(0); // 列表已在 Service 层升序（最旧在前）
            oldestId = first.getId();
            oldestCreatedAt = first.getCreatedAt() == null ? null : first.getCreatedAt().toString();
        }
        return new MessagePageDto(dtos, hasMore, oldestId, oldestCreatedAt);
    }
}
