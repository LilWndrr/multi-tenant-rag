package com.example.api.tenant;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;

@Component
@RequestScope
public class TenantContext {
    private UUID tenantId;
    private UUID apiKeyId;
    private String languageProfile;

    public UUID getTenantId() {
        if(tenantId==null){
            throw new IllegalStateException("TenantContext not set: request was not authenticated");
        }
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {

        this.tenantId = tenantId;
    }

    public String getLanguageProfile() {
        return languageProfile;
    }

    public void setLanguageProfile(String languageProfile) {
        this.languageProfile = languageProfile;
    }

    public UUID getApiKeyId() {

        return apiKeyId;
    }

    public void setApiKeyId(UUID apiKeyId) {
        this.apiKeyId = apiKeyId;
    }

    // set(...) called once by the filter, getters for everyone else
}