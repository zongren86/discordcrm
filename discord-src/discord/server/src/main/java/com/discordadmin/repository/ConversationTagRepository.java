package com.discordadmin.repository;

import com.discordadmin.entity.ConversationTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConversationTagRepository extends JpaRepository<ConversationTag, Long> {

    List<ConversationTag> findByConversationId(Long conversationId);

    List<ConversationTag> findByMerchantId(Long merchantId);

    void deleteByConversationId(Long conversationId);

    void deleteByConversationIdAndId(Long conversationId, Long id);

    @Query("SELECT DISTINCT ct.name FROM ConversationTag ct WHERE (ct.merchantId = :merchantId OR :merchantId IS NULL) ORDER BY ct.name")
    List<String> findDistinctNamesByMerchantId(@Param("merchantId") Long merchantId);

    @Query("SELECT ct FROM ConversationTag ct WHERE ct.conversationId = :conversationId AND ct.name IN :names")
    List<ConversationTag> findByConversationIdAndNames(@Param("conversationId") Long conversationId,
                                                        @Param("names") List<String> names);
}
