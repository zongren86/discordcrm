package com.discordadmin.repository;

import com.discordadmin.entity.MessageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

    List<MessageTemplate> findByMerchantIdOrderBySortOrderAsc(Long merchantId);

    List<MessageTemplate> findByMerchantIdAndCategoryOrderBySortOrderAsc(Long merchantId, String category);

    List<MessageTemplate> findByCategoryOrderBySortOrderAsc(String category);
}
