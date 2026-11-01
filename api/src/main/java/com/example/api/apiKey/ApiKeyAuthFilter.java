package com.example.api.apiKey;

import com.example.api.helpers.ApiKeyGenerator;
import com.example.api.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;


public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";


    private final ApiKeyRepository apiKeyRepository;
    private final TenantContext tenantContext;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeyRepository, TenantContext tenantContext) {
        this.apiKeyRepository = apiKeyRepository;
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if(header==null || !header.startsWith("Bearer")){
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,"Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String key = header.substring(PREFIX.length()).trim();
        if (header.startsWith(PREFIX) && !key.startsWith("rk_")){
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,"Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        byte[] hashKey = ApiKeyGenerator.hash(key);
        AuthenticatedKey authenticatedKey =  apiKeyRepository.getApiKeyByHash(hashKey).orElse(null);
        if(authenticatedKey == null){
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,"Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
            tenantContext.setTenantId(authenticatedKey.tenantId());
            tenantContext.setApiKeyId(authenticatedKey.apiKeyId());
            tenantContext.setLanguageProfile(authenticatedKey.languageProfile());
            chain.doFilter(request,response);
        }

    }
