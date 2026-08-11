package com.discordadmin.repository;

import com.discordadmin.entity.Role;
import com.discordadmin.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    List<RolePermission> findByRole(Role role);

    void deleteByRole(Role role);

    long deleteByRoleId(Long roleId);
}
