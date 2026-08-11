package com.discordadmin.controller;

import com.discordadmin.entity.SysFeature;
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

    @GetMapping
    public List<SysFeature> list() {
        return repo.findAllByOrderBySortOrderAsc();
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
