package com.example.api.usage;

import com.example.api.tenant.TenantContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

// The calling tenant's own usage. Days are UTC, both ends included; by default the last 30 days.
@RestController
@RequestMapping("/v1")
public class UsageController {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 366;

    private final UsageRepository usageRepository;
    private final TenantContext tenantContext;
    private final Clock clock;

    public UsageController(UsageRepository usageRepository, TenantContext tenantContext) {
        this.usageRepository = usageRepository;
        this.tenantContext = tenantContext;
        this.clock = Clock.systemUTC();
    }

    @GetMapping("/usage")
    public ResponseEntity<UsageReport> usage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(UsageReport.of(start, end,
                usageRepository.forTenant(tenantContext.getTenantId(), start, end)));
    }
}
