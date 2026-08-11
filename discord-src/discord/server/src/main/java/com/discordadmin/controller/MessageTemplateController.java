package com.discordadmin.controller;

import com.discordadmin.entity.MessageTemplate;
import com.discordadmin.repository.MessageTemplateRepository;
import com.discordadmin.security.SecurityUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/message-templates")
public class MessageTemplateController {

    private final MessageTemplateRepository templateRepository;

    public MessageTemplateController(MessageTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /** 获取当前商户的所有模板 */
    @GetMapping
    public List<Map<String, Object>> listTemplates(
            @RequestParam(required = false) String category) {
        Long merchantId = SecurityUtils.currentMerchantId();
        List<MessageTemplate> templates;
        if (SecurityUtils.isPlatformAdmin()) {
            if (category != null && !category.isBlank()) {
                templates = templateRepository.findByCategoryOrderBySortOrderAsc(category);
            } else {
                templates = templateRepository.findAll().stream()
                        .sorted(Comparator.comparing(MessageTemplate::getSortOrder))
                        .toList();
            }
        } else {
            if (category != null && !category.isBlank()) {
                templates = templateRepository.findByMerchantIdAndCategoryOrderBySortOrderAsc(merchantId, category);
            } else {
                templates = templateRepository.findByMerchantIdOrderBySortOrderAsc(merchantId);
            }
        }
        return templates.stream().map(this::toMap).toList();
    }

    /** 获取模板分类列表 */
    @GetMapping("/categories")
    public List<String> listCategories() {
        Long merchantId = SecurityUtils.currentMerchantId();
        List<MessageTemplate> templates;
        if (SecurityUtils.isPlatformAdmin()) {
            templates = templateRepository.findAll();
        } else {
            templates = templateRepository.findByMerchantIdOrderBySortOrderAsc(merchantId);
        }
        return templates.stream()
                .map(MessageTemplate::getCategory)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /** 创建模板 */
    @PostMapping
    public Map<String, Object> create(@RequestBody TemplateRequest request) {
        Long merchantId = SecurityUtils.currentMerchantId();
        if (!SecurityUtils.isPlatformAdmin()) {
            merchantId = SecurityUtils.currentMerchantId();
        }

        MessageTemplate template = new MessageTemplate();
        template.setMerchantId(merchantId);
        template.setTitle(request.title());
        template.setContent(request.content());
        template.setCategory(request.category());
        template.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);

        return toMap(templateRepository.save(template));
    }

    /** 更新模板 */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id,
                                       @RequestBody TemplateRequest request) {
        MessageTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在"));
        SecurityUtils.checkMerchantAccess(template.getMerchantId());

        if (request.title() != null) template.setTitle(request.title());
        if (request.content() != null) template.setContent(request.content());
        if (request.category() != null) template.setCategory(request.category());
        if (request.sortOrder() != null) template.setSortOrder(request.sortOrder());
        template.setUpdatedAt(Instant.now());

        return toMap(templateRepository.save(template));
    }

    /** 删除模板 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        MessageTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在"));
        SecurityUtils.checkMerchantAccess(template.getMerchantId());
        templateRepository.deleteById(id);
    }

    /** 批量删除模板 */
    @DeleteMapping("/batch")
    public Map<String, Object> batchDelete(@RequestBody BatchDeleteRequest request) {
        int deleted = 0;
        for (Long id : request.ids()) {
            MessageTemplate template = templateRepository.findById(id).orElse(null);
            if (template != null) {
                SecurityUtils.checkMerchantAccess(template.getMerchantId());
                templateRepository.deleteById(id);
                deleted++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", deleted);
        return result;
    }

    private Map<String, Object> toMap(MessageTemplate t) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", t.getId());
        item.put("title", t.getTitle());
        item.put("content", t.getContent());
        item.put("category", t.getCategory());
        item.put("sortOrder", t.getSortOrder());
        item.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        item.put("updatedAt", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
        return item;
    }

    public record TemplateRequest(String title, String content, String category, Integer sortOrder) {}
    public record BatchDeleteRequest(List<Long> ids) {}
}
