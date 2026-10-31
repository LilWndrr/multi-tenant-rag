package com.example.api.apiKey;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ApiKey(UUID id, String name, String prefix, OffsetDateTime createdAt,
                     OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {}
