package com.discordadmin.repository;

import com.discordadmin.entity.AccountBindingHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AccountBindingHistoryRepository extends JpaRepository<AccountBindingHistory, Long> {

    /** 根据编号ID查询绑定历史（按时间倒序） */
    Page<AccountBindingHistory> findByAccountNumberIdOrderByChangedAtDesc(Long accountNumberId, Pageable pageable);

    /** 根据编号ID查询所有绑定历史 */
    List<AccountBindingHistory> findByAccountNumberIdOrderByChangedAtDesc(Long accountNumberId);

    /** 根据操作人ID查询 */
    List<AccountBindingHistory> findByOperatorIdOrderByChangedAtDesc(Long operatorId);

    /** 根据编号ID查询最近一条记录 */
    @Query("SELECT h FROM AccountBindingHistory h WHERE h.accountNumberId = :accountNumberId ORDER BY h.changedAt DESC LIMIT 1")
    AccountBindingHistory findLatestByAccountNumberId(@Param("accountNumberId") Long accountNumberId);
}
