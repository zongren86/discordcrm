package com.discordadmin.repository;

import com.discordadmin.entity.ReminderRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReminderRuleRepository extends JpaRepository<ReminderRule, Long> {

    List<ReminderRule> findByMerchantIdOrderByIdDesc(Long merchantId);

    List<ReminderRule> findByMerchantIdIsNullOrderByIdDesc();

    List<ReminderRule> findByEnabledTrue();
}
