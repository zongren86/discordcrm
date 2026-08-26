package com.discordadmin.repository;

import com.discordadmin.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    List<Role> findAllByOrderByIdDesc();

    List<Role> findByMerchantIdOrderByIdDesc(Long merchantId);

    List<Role> findByMerchantIdIsNullOrderByIdDesc();

    List<Role> findByRoleTypeOrderByIdDesc(Role.RoleType roleType);

    Optional<Role> findByCode(String code);

    @Query("SELECT r FROM Role r WHERE r.roleType = 'PLATFORM' AND r.merchantId IS NULL")
    List<Role> findAllPlatformRoles();

    @Query("SELECT DISTINCT r FROM Role r WHERE r.roleType = 'MERCHANT' AND (r.merchantId = :merchantId OR r.merchantId IS NULL)")
    List<Role> findMerchantRolesForOwner(@Param("merchantId") Long merchantId);

    @Query("SELECT DISTINCT r FROM Role r LEFT JOIN FETCH r.features WHERE r.id IN :ids")
    List<Role> findByIdInWithFeatures(@Param("ids") Collection<Long> ids);
}
