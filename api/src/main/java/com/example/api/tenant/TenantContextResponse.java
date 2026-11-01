package com.example.api.tenant;

import java.util.UUID;

public record TenantContextResponse(UUID tenantId, String languageProfile) {
}
