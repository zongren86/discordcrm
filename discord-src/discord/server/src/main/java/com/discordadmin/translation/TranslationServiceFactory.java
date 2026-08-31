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
     * 翻译文本 - 根据配置动态选择翻译渠道
     *
     * 优先级：
     *  1) 若商户已配置 AI 翻译（provider != free 且有 apiKey）→ 走 Qianwen（内部已自带 model + endpoint 回退）
     *     只有当 Qianwen 两次回退都拿不到译文时，才兜底尝试免费翻译（免费翻译在国内网络经常超时/302，基本不可用）
     *  2) 未配置 AI → 直接走免费翻译
     *
     * 注意：绝不允许"译文 == 原文"糊弄（有些免费翻译在失败后直接 return Optional.of(原文)），调用方若发现译文 == 原文应视为翻译失败。
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

        boolean hasAiSetting = setting != null && Boolean.TRUE.equals(setting.getEnabled())
                && setting.getProvider() != null
                && !"free".equals(setting.getProvider())
                && setting.getApiKey() != null && !setting.getApiKey().isBlank();

        Optional<String> aiResult = Optional.empty();
        if (hasAiSetting) {
            try {
                aiResult = qianwenService.translate(text, targetLanguage, setting);
                if (aiResult.isPresent() && !aiResult.get().isBlank()
                        && !aiResult.get().equalsIgnoreCase(text.trim())) {
                    return aiResult;
                }
                log.info("AI翻译未产出有效译文(空或等于原文): merchant={} provider={} model={}",
                        merchantId, setting.getProvider(), setting.getModel());
            } catch (Exception e) {
                log.warn("AI翻译异常 merchant={}: {}, 继续尝试免费翻译", merchantId, e.getMessage());
            }
        }

        // 兜底：免费翻译（仅"未配置 AI"或"AI 明确失败"时用；失败后仍可能返回原文，由调用方处理）
        Optional<String> free = freeGoogleService.translate(text, targetLanguage);
        if (free.isPresent() && !free.get().isBlank() && !free.get().equalsIgnoreCase(text.trim())) {
            log.debug("免费翻译兜底成功 merchant={}", merchantId);
            return free;
        }
        // 最终兜底：AI 和免费翻译都没产出有效译文（都返回原文或为空）
        // 一律返回 empty，让调用方 MessageService 用 .orElse(content) 自行处理
        if (aiResult.isPresent() && !aiResult.get().isBlank()
                && !aiResult.get().equalsIgnoreCase(text.trim())) {
            log.warn("AI翻译与免费翻译均未产出有效译文，但AI返回了不同结果，使用AI结果: merchant={}", merchantId);
            return aiResult;
        }
        log.warn("所有翻译渠道均未产出有效译文(返回原文或空): textLen={} targetLang={} merchant={}",
                text.length(), targetLanguage, merchantId);
        return Optional.empty();
    }

    /**
     * 翻译文本 - 使用默认配置（向后兼容）
     */
    public Optional<String> translate(String text, String targetLanguage) {
        return translate(text, targetLanguage, null);
    }
}
