package com.example.common.model;

import java.util.UUID;

public record ModelCall(UUID tenantId,String purpose,String model) {
}
