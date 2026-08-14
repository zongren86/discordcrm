package com.discordadmin.translation;

import com.discordadmin.entity.AISetting;
import com.discordadmin.repository.AISettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 翻译服务工厂 - 根据配置动态选择翻译渠道
 */
@Service
public class TranslationServiceFactory {

    private static final Logger log = LoggerFactory.getLogger(TranslationServiceFactory.class);

    @Autowired
    private FreeGoogleTranslationService freeGoogleService;

    @Autowired
    private QianwenTranslationService qianwenService;

    @Autowired
    private AISettingRepository aiSettingRepository;

    /**
     * 翻译文本 - 根据配置选择翻译渠道
     * @param text 要翻译的文本
     * @param targetLanguage 目标语言 (e.g., "zh-CN", "en")
     * @param merchantId 商户ID
     * @return 翻译后的文本
     */
    public Optional<String> translate(String text, String targetLanguage, Long merchantId) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        // 查询翻译配置
        AISetting setting = null;
        if (merchantId != null) {
            setting = aiSettingRepository.findByMerchantIdAndFeature(merchantId, "translate").orElse(null);
        }
        
        // 如果配置了 AI 翻译且已启用，尝试使用 AI
        if (setting != null && Boolean.TRUE.equals(setting.getEnabled()) 
                && setting.getProvider() != null 
                && !"free".equals(setting.getProvider())
                && setting.getApiKey() != null && !setting.getApiKey().isBlank()) {
            try {
                Optional<String> result = qianwenService.translate(text, targetLanguage, setting);
                if (result.isPresent()) {
                    return result;
                }
                log.debug("AI翻译失败，降级到免费翻译");
            } catch (Exception e) {
                log.warn("AI翻译异常: {}, 降级到免费翻译", e.getMessage());
            }
        }

        // 使用免费翻译（Google + MyMemory）
        return freeGoogleService.translate(text, targetLanguage);
    }

    /**
     * 翻译文本 - 使用默认配置（向后兼容）
     */
    public Optional<String> translate(String text, String targetLanguage) {
        return translate(text, targetLanguage, null);
    }
}
