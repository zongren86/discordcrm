package com.discordadmin.controller;

import com.discordadmin.entity.AISetting;
import com.discordadmin.repository.AISettingRepository;
import com.discordadmin.repository.MerchantRepository;
import com.discordadmin.security.SecurityUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai-settings")
public class AISettingController {

    private final AISettingRepository aiSettingRepository;
    private final MerchantRepository merchantRepository;

    public AISettingController(AISettingRepository aiSettingRepository, MerchantRepository merchantRepository) {
        this.aiSettingRepository = aiSettingRepository;
        this.merchantRepository = merchantRepository;
    }

    @GetMapping
    public List<AISetting> list(@RequestHeader(value = "X-Merchant-Id", required = false) Long merchantIdHeader) {
        Long merchantId = resolveMerchantId(merchantIdHeader);
        return aiSettingRepository.findByMerchantIdOrderByFeatureAsc(merchantId);
    }

    @GetMapping("/feature/{feature}")
    public AISetting getByFeature(@PathVariable String feature,
                                   @RequestHeader(value = "X-Merchant-Id", required = false) Long merchantIdHeader) {
        Long merchantId = resolveMerchantId(merchantIdHeader);
        AISetting setting = aiSettingRepository.findByMerchantIdAndFeature(merchantId, feature).orElse(null);
        if (setting == null) {
            setting = new AISetting();
            setting.setFeature(feature);
            setting.setMerchantId(merchantId);
            setting.setEnabled(false);
        }
        return setting;
    }

    @PostMapping
    public AISetting createOrUpdate(@RequestBody AISettingRequest req,
                                     @RequestHeader(value = "X-Merchant-Id", required = false) Long merchantIdHeader) {
        Long merchantId = resolveMerchantId(merchantIdHeader);
        AISetting setting = aiSettingRepository.findByMerchantIdAndFeature(merchantId, req.feature())
                .orElseGet(AISetting::new);
        if (setting.getId() == null) {
            setting.setFeature(req.feature());
            setting.setMerchantId(merchantId);
        }
        setting.setEnabled(req.enabled() != null ? req.enabled() : false);
        setting.setProvider(req.provider());
        setting.setModel(req.model());
        setting.setApiEndpoint(req.apiEndpoint());
        setting.setApiKey(req.apiKey());
        if (req.temperature() != null) setting.setTemperature(req.temperature());
        if (req.maxTokens() != null) setting.setMaxTokens(req.maxTokens());
        setting.setSystemPrompt(req.systemPrompt());
        if (req.thinking() != null) setting.setThinking(req.thinking());
        if (req.webSearch() != null) setting.setWebSearch(req.webSearch());
        if (req.languageDetectionMode() != null) setting.setLanguageDetectionMode(req.languageDetectionMode());
        setting.setUpdatedAt(Instant.now());
        return aiSettingRepository.save(setting);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        aiSettingRepository.deleteById(id);
        return Map.of("success", true);
    }

    private Long resolveMerchantId(Long merchantIdHeader) {
        if (SecurityUtils.isPlatformAdmin()) {
            if (merchantIdHeader != null) {
                return merchantIdHeader;
            }
            return null;
        }
        return SecurityUtils.currentMerchantId();
    }

    public record AISettingRequest(String feature, Boolean enabled, String provider, String model,
                                   String apiEndpoint, String apiKey, Double temperature,
                                   Integer maxTokens, String systemPrompt,
                                   Boolean thinking, Boolean webSearch,
                                   String languageDetectionMode) {}
}
