package com.example.api.apiKey;

import java.util.UUID;

public record AuthenticatedKey(UUID apiKeyId, UUID tenantId, String languageProfile) {
}
