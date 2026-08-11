package com.discordadmin.controller;

import com.discordadmin.entity.Role;
import com.discordadmin.entity.SysFeature;
import com.discordadmin.repository.MerchantRepository;
import com.discordadmin.repository.RoleRepository;
import com.discordadmin.repository.SysFeatureRepository;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Collections;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleRepository roleRepository;
    private final MerchantRepository merchantRepository;
    private final SysFeatureRepository featureRepository;
    private final AuditService auditService;

    @GetMapping
    public List<Role> list() {
        Long merchantId = SecurityUtils.currentMerchantId();
        if (SecurityUtils.isPlatformAdmin()) {
            return roleRepository.findAllByOrderByIdDesc();
        }
        List<Role> owned = roleRepository.findByMerchantIdOrderByIdDesc(merchantId);
        List<Role> platformRoles = roleRepository.findMerchantRolesForOwner(merchantId);
        Set<Role> merged = new LinkedHashSet<>();
        merged.addAll(owned);
        merged.addAll(platformRoles);
        return new ArrayList<>(merged);
    }

    @PostMapping
    @Transactional
    public Role create(@RequestBody RoleRequest req) {
        Long merchantId = SecurityUtils.currentMerchantId();
        Role.RoleType roleType = req.roleType() != null
                ? Role.RoleType.valueOf(req.roleType())
                : Role.RoleType.MERCHANT;

        if (SecurityUtils.isPlatformAdmin()) {
            if (roleType == Role.RoleType.PLATFORM) {
                merchantId = null;
            } else if (req.merchantId() != null) {
                merchantId = req.merchantId();
            }
        }

        Role role = new Role();
        role.setName(req.name());
        role.setCode(req.code());
        role.setDescription(req.description());
        role.setBuiltin(false);
        role.setEnabled(true);
        role.setRoleType(roleType);
        role.setMerchantId(merchantId);
        
        if (req.merchantIds() != null && !req.merchantIds().isEmpty()) {
            role.setMerchantIds(new HashSet<>(req.merchantIds()));
        }
        
        if (req.featureIds() != null && !req.featureIds().isEmpty()) {
            List<SysFeature> features = featureRepository.findAllById(req.featureIds());
            role.setFeatures(new HashSet<>(features));
        }
        
        Role saved = roleRepository.save(role);
        auditService.log("role", "CREATE", "Role", String.valueOf(saved.getId()), "name=" + saved.getName());
        return saved;
    }

    @PutMapping("/{id}")
    @Transactional
    public Role update(@PathVariable Long id, @RequestBody RoleRequest req) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        if (req.name() != null) role.setName(req.name());
        if (req.description() != null) role.setDescription(req.description());
        if (req.enabled() != null) role.setEnabled(req.enabled());
        if (req.roleType() != null) {
            role.setRoleType(Role.RoleType.valueOf(req.roleType()));
        }
        if (req.merchantIds() != null) {
            role.getMerchantIds().clear();
            role.getMerchantIds().addAll(req.merchantIds());
        }
        if (req.featureIds() != null) {
            List<SysFeature> features = featureRepository.findAllById(req.featureIds());
            role.setFeatures(new HashSet<>(features));
        }
        auditService.log("role", "UPDATE", "Role", String.valueOf(id), "name=" + role.getName());
        return roleRepository.save(role);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable Long id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        if (Boolean.TRUE.equals(role.getBuiltin())) {
            throw new IllegalArgumentException("系统预置角色不可删除");
        }
        roleRepository.delete(role);
        auditService.log("role", "DELETE", "Role", String.valueOf(id), "name=" + role.getName());
        return Map.of("success", true);
    }

    /** 获取角色适用的商户列表 */
    @GetMapping("/{id}/merchant-ids")
    public Set<Long> getMerchantIds(@PathVariable Long id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        return role.getMerchantIds();
    }

    /** 获取角色关联的功能codes列表（按code返回，与前端tree node-key对齐） */
    @GetMapping("/{id}/features")
    @Transactional(readOnly = true)
    public List<String> getFeatures(@PathVariable Long id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        return role.getFeatures().stream()
                .map(SysFeature::getCode)
                .collect(Collectors.toList());
    }

    /** 设置角色的功能权限（接收featureCodes列表） */
    @PutMapping("/{id}/features")
    @Transactional
    public Map<String, Object> setFeatures(@PathVariable Long id,
                                            @RequestBody Map<String, List<String>> body) {
        List<String> featureCodes = body.get("featureCodes");
        if (featureCodes == null) {
            featureCodes = Collections.emptyList();
        }
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        List<SysFeature> features = featureCodes.stream()
                .map(code -> featureRepository.findByCode(code).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        role.setFeatures(new HashSet<>(features));
        roleRepository.save(role);
        auditService.log("role", "UPDATE_FEATURES", "Role", String.valueOf(id),
                "featureCodes=" + featureCodes.size());
        return Map.of("success", true);
    }

    /** 获取功能目录（树形结构） */
    @GetMapping("/feature-catalog")
    public List<Map<String, Object>> featureCatalog() {
        List<SysFeature> all = featureRepository.findAllByOrderBySortOrderAsc();
        return buildFeatureTree(all, null);
    }

    /** 获取所有商户列表（用于适用商户选择） */
    @GetMapping("/merchants")
    public List<Map<String, Object>> listMerchants() {
        return merchantRepository.findAll().stream()
                .map(m -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", m.getId());
                    item.put("name", m.getName());
                    item.put("code", m.getCode());
                    return item;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildFeatureTree(List<SysFeature> all, Long parentId) {
        return all.stream()
                .filter(f -> Objects.equals(f.getParentId(), parentId))
                .map(f -> {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("id", f.getId());
                    node.put("code", f.getCode());
                    node.put("name", f.getName());
                    node.put("type", f.getType());
                    node.put("btnType", f.getBtnType());
                    node.put("icon", f.getIcon());
                    node.put("routePath", f.getRoutePath());
                    node.put("sortOrder", f.getSortOrder());
                    List<Map<String, Object>> children = buildFeatureTree(all, f.getId());
                    node.put("children", children);
                    return node;
                })
                .collect(Collectors.toList());
    }

    public record RoleRequest(String name, String code, String description,
                              Boolean enabled, String roleType,
                              Long merchantId, List<Long> merchantIds,
                              List<Long> featureIds) {}
}
