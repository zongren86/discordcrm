package com.discordadmin.repository;

import com.discordadmin.entity.AgentAccountNumberRel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentAccountNumberRelRepository extends JpaRepository<AgentAccountNumberRel, Long> {

    /** 根据用户ID查询所有关联 */
    List<AgentAccountNumberRel> findByAgentId(Long agentId);

    /** 根据编号ID查询所有关联 */
    List<AgentAccountNumberRel> findByAccountNumberId(Long accountNumberId);

    /** 根据编号ID列表批量查询所有关联 */
    List<AgentAccountNumberRel> findByAccountNumberIdIn(List<Long> accountNumberIds);

    /** 根据用户ID和编号ID查询 */
    Optional<AgentAccountNumberRel> findByAgentIdAndAccountNumberId(Long agentId, Long accountNumberId);

    /** 查询用户关联的编号ID列表 */
    @Query("SELECT r.accountNumberId FROM AgentAccountNumberRel r WHERE r.agentId = :agentId")
    List<Long> findAccountNumberIdsByAgentId(@Param("agentId") Long agentId);

    /** 删除用户不在指定范围内的关联 */
    @Modifying
    @Query("DELETE FROM AgentAccountNumberRel r WHERE r.agentId = :agentId AND r.accountNumberId NOT IN :accountNumberIds")
    void deleteByAgentIdAndAccountNumberIdNotIn(@Param("agentId") Long agentId,
                                                 @Param("accountNumberIds") List<Long> accountNumberIds);

    /** 删除单条关联 */
    @Modifying
    void deleteByAgentIdAndAccountNumberId(Long agentId, Long accountNumberId);

    /** 批量查询 */
    List<AgentAccountNumberRel> findByAgentIdIn(List<Long> agentIds);
}
