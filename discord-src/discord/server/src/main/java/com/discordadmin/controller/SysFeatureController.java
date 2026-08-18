package com.discordadmin.controller;

import com.discordadmin.entity.Role;
import com.discordadmin.entity.SysFeature;
import com.discordadmin.repository.RoleRepository;
import com.discordadmin.repository.SysFeatureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/system/features")
@RequiredArgsConstructor
public class SysFeatureController {

    private final SysFeatureRepository repo;
    private final RoleRepository roleRepository;

    @GetMapping
    public List<SysFeature> list() {
        return repo.findAllByOrderBySortOrderAsc();
    }

    @PostMapping("/init-data")
    @Transactional
    public Map<String, Object> initData() {
        Map<String, Object> result = new HashMap<>();
        List<String> created = new ArrayList<>();
        List<String> updated = new ArrayList<>();

        // 确保一级菜单存在
        SysFeature service = ensureFeature("service", "服务", null, "MENU_1", null, "Shop", 4, created, updated);
        
        // 确保二级菜单存在
        ensureFeature("guilds", "服务器列表", service.getId(), "MENU_2", "/guilds", "OfficeBuilding", 1, created, updated);
        ensureFeature("guild-members", "服务器成员", service.getId(), "MENU_2", "/guild-members", "User", 2, created, updated);
        ensureFeature("friend-manage", "好友管理", service.getId(), "MENU_2", "/emulator", "Monitor", 3, created, updated);

        // 确保 platform_admin 角色包含所有功能权限
        List<Role> roles = roleRepository.findAll();
        for (Role role : roles) {
            if ("platform_admin".equals(role.getCode())) {
                Set<SysFeature> currentFeatures = new HashSet<>(role.getFeatures());
                Set<String> existingCodes = currentFeatures.stream()
                    .map(SysFeature::getCode)
                    .collect(Collectors.toSet());
                
                // 添加缺失的功能
                List<String> requiredCodes = Arrays.asList(
                    "dashboard", "chat", "customer", "service", "config", "system", "log",
                    "account-numbers", "accounts", "customers", "guilds", "guild-members",
                    "friend-manage", "ai-settings", "users", "roles", "features", "audit"
                );
                
                for (String code : requiredCodes) {
                    if (!existingCodes.contains(code)) {
                        repo.findByCode(code).ifPresent(currentFeatures::add);
                    }
                }
                
                role.setFeatures(currentFeatures);
                roleRepository.save(role);
                updated.add("platform_admin 角色权限已更新");
            }
        }

        result.put("success", true);
        result.put("created", created);
        result.put("updated", updated);
        return result;
    }

    private SysFeature ensureFeature(String code, String name, Long parentId, String type, String routePath, String icon, Integer sortOrder, List<String> created, List<String> updated) {
        Optional<SysFeature> existing = repo.findByCode(code);
        if (existing.isPresent()) {
            SysFeature f = existing.get();
            boolean updated_flag = false;
            if (!Objects.equals(f.getName(), name)) { f.setName(name); updated_flag = true; }
            if (!Objects.equals(f.getParentId(), parentId)) { f.setParentId(parentId); updated_flag = true; }
            if (!Objects.equals(f.getType(), type)) { f.setType(type); updated_flag = true; }
            if (!Objects.equals(f.getRoutePath(), routePath)) { f.setRoutePath(routePath); updated_flag = true; }
            if (!Objects.equals(f.getIcon(), icon)) { f.setIcon(icon); updated_flag = true; }
            if (!Objects.equals(f.getSortOrder(), sortOrder)) { f.setSortOrder(sortOrder); updated_flag = true; }
            if (updated_flag) {
                repo.save(f);
                updated.add(code);
            }
            return f;
        } else {
            SysFeature f = new SysFeature();
            f.setCode(code);
            f.setName(name);
            f.setParentId(parentId);
            f.setType(type);
            f.setRoutePath(routePath);
            f.setIcon(icon);
            f.setSortOrder(sortOrder);
            created.add(code);
            return repo.save(f);
        }
    }

    @GetMapping("/tree")
    public List<Map<String, Object>> tree() {
        List<SysFeature> all = repo.findAllByOrderBySortOrderAsc();
        return buildTree(all, null);
    }

    @PostMapping
    @Transactional
    public SysFeature create(@RequestBody SysFeature req) {
        if (repo.existsByCode(req.getCode())) {
            throw new RuntimeException("功能代码已存在");
        }
        return repo.save(req);
    }

    @PutMapping("/{id}")
    @Transactional
    public SysFeature update(@PathVariable Long id, @RequestBody SysFeature req) {
        SysFeature f = repo.findById(id).orElseThrow(() -> new RuntimeException("功能不存在"));
        f.setName(req.getName());
        f.setType(req.getType());
        f.setBtnType(req.getBtnType());
        f.setIcon(req.getIcon());
        f.setRoutePath(req.getRoutePath());
        f.setParentId(req.getParentId());
        f.setSortOrder(req.getSortOrder());
        return repo.save(f);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable Long id) {
        SysFeature f = repo.findById(id).orElseThrow(() -> new RuntimeException("功能不存在"));
        List<SysFeature> all = repo.findAllByOrderBySortOrderAsc();
        Set<Long> toDelete = new HashSet<>();
        collectDescendants(id, all, toDelete);
        toDelete.add(id);
        all.stream().filter(x -> toDelete.contains(x.getId())).forEach(repo::delete);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    private void collectDescendants(Long parentId, List<SysFeature> all, Set<Long> result) {
        all.stream()
            .filter(f -> parentId.equals(f.getParentId()))
            .forEach(f -> {
                result.add(f.getId());
                collectDescendants(f.getId(), all, result);
            });
    }

    private List<Map<String, Object>> buildTree(List<SysFeature> all, Long parentId) {
        return all.stream()
            .filter(f -> Objects.equals(f.getParentId(), parentId))
            .map(f -> {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", f.getId());
                node.put("code", f.getCode());
                node.put("name", f.getName());
                node.put("parentId", f.getParentId());
                node.put("type", f.getType());
                node.put("btnType", f.getBtnType());
                node.put("icon", f.getIcon());
                node.put("routePath", f.getRoutePath());
                node.put("sortOrder", f.getSortOrder());
                List<Map<String, Object>> children = buildTree(all, f.getId());
                node.put("children", children);
                return node;
            })
            .collect(Collectors.toList());
    }
}
