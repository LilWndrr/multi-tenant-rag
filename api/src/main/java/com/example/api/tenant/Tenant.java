package com.example.api.tenant;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Tenant(UUID id, String name, String languageProfile, OffsetDateTime createdAt) {}
