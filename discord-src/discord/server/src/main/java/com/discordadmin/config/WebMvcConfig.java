package com.discordadmin.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class WebMvcConfig {

    /**
     * 注册 MdcTraceFilter 为 Servlet 容器级 Filter
     * Order = HIGHEST_PRECEDENCE，确保在 Spring Security Filter Chain 之前执行
     */
    @Bean
    public FilterRegistrationBean<MdcTraceFilter> mdcTraceFilterRegistration(MdcTraceFilter filter) {
        FilterRegistrationBean<MdcTraceFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("mdcTraceFilter");
        // 不让它进 Spring Security 的 filter 链（已经在外面执行了）
        reg.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ASYNC);
        return reg;
    }
}
