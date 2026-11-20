package com.example.api.usage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// GET /v1/usage: one row per UTC day, purpose and model, and the totals over all of them
public record UsageReport(LocalDate from, LocalDate to, Totals total, List<Row> days) {

    public record Row(LocalDate day, String purpose, String model, int requests, int errors,
                      long inputTokens, long outputTokens, long cachedTokens, BigDecimal costUsd) {}

    public record Totals(long requests, long errors, long inputTokens, long outputTokens, long cachedTokens,
                         BigDecimal costUsd) {}

    static UsageReport of(LocalDate from, LocalDate to, List<Row> rows) {
        Totals total = new Totals(
                rows.stream().mapToLong(Row::requests).sum(),
                rows.stream().mapToLong(Row::errors).sum(),
                rows.stream().mapToLong(Row::inputTokens).sum(),
                rows.stream().mapToLong(Row::outputTokens).sum(),
                rows.stream().mapToLong(Row::cachedTokens).sum(),
                rows.stream().map(Row::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add));
        return new UsageReport(from, to, total, rows);
    }
}
