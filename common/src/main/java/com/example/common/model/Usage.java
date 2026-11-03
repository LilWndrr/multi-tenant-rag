package com.example.common.model;

import java.math.BigDecimal;

public record Usage(int inputTokens, int outputTokens, int cachedTokens, BigDecimal costUsd    ) {
}
