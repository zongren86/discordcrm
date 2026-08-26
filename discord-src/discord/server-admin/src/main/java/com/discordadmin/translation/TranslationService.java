package com.discordadmin.translation;

import java.util.Optional;

public interface TranslationService {
    /**
     * 尝试把文本翻译成目标语言(targetLanguage如"zh-CN"/"en")，失败(网络问题/限流/空文本等)时
     * 返回empty，调用方必须把这当成非致命错误处理，不能让翻译失败影响主流程(收发消息)。
     */
    Optional<String> translate(String text, String targetLanguage);
}
