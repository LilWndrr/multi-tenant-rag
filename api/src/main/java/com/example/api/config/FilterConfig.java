package com.example.api.config;

import com.example.api.admin.AdminTokenFilter;
import com.example.api.apiKey.ApiKeyAuthFilter;
import com.example.api.apiKey.ApiKeyRepository;
import com.example.api.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    @Bean
    FilterRegistrationBean<AdminTokenFilter> adminTokenFilter(@Value("${rag.admin.token}") String token) {
        var reg = new FilterRegistrationBean<>(new AdminTokenFilter(token));
        reg.addUrlPatterns("/admin/*");
        return reg;
    }

    @Bean
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(ApiKeyRepository repo, TenantContext ctx) {
        var bean = new FilterRegistrationBean<>(new ApiKeyAuthFilter(repo, ctx));
        bean.addUrlPatterns("/v1/*");
        return bean;
    }
}
