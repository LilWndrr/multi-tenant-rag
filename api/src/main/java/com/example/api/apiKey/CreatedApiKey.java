package com.example.api.apiKey;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreatedApiKey(UUID id, String name, String prefix, OffsetDateTime createdAt, String key) {
    @Override
    public String toString() {        // records print every field by default, the key too
        return "CreatedApiKey[id=" + id + ", prefix=" + prefix + "]";
    }
}
