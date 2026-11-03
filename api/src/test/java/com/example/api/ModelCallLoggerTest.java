package com.example.api;

import com.example.api.tenant.Tenant;
import com.example.api.tenant.TenantService;
import com.example.common.model.ModelCall;
import com.example.common.model.ModelCallLogger;
import com.example.common.model.Usage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// lives in api because the Flyway migrations and Testcontainers setup are here
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ModelCallLoggerTest {

    @Autowired
    ModelCallLogger logger;
    @Autowired
    TenantService tenantService;
    @Autowired
    JdbcClient jdbc;

    private Map<String, Object> rowFor(String purpose) {
        // unique purpose per test, so each test finds exactly its own row
        return jdbc.sql("select * from model_calls where purpose = :purpose")
                .param("purpose", purpose)
                .query().singleRow();
    }

    @Test
    void successfulCall_writesAllColumns() {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        String purpose = "test-" + UUID.randomUUID();
        ModelCall call = new ModelCall(tenant.id(), purpose, "requested/model");
        Usage usage = new Usage(120, 45, 20, new BigDecimal("0.00012345"));

        logger.createModelCall(call, "openrouter", "provider/model-v1", "ok", usage, 830);

        Map<String, Object> row = rowFor(purpose);
        assertThat(row.get("tenant_id")).isEqualTo(tenant.id());
        assertThat(row.get("provider")).isEqualTo("openrouter");
        assertThat(row.get("model")).isEqualTo("provider/model-v1");
        assertThat(row.get("status")).isEqualTo("ok");
        assertThat(row.get("input_tokens")).isEqualTo(120);
        assertThat(row.get("output_tokens")).isEqualTo(45);
        assertThat(row.get("cached_tokens")).isEqualTo(20);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.00012345");
        assertThat(row.get("latency_ms")).isEqualTo(830);
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void failedSystemCall_withoutTenantOrCost_isStillLogged() {
        String purpose = "test-" + UUID.randomUUID();
        ModelCall call = new ModelCall(null, purpose, "requested/model");

        logger.createModelCall(call, "openrouter", "requested/model", "error", new Usage(0, 0, 0, null), 120);

        Map<String, Object> row = rowFor(purpose);
        assertThat(row.get("tenant_id")).isNull();
        assertThat(row.get("status")).isEqualTo("error");
        assertThat(row.get("input_tokens")).isEqualTo(0);
        assertThat(row.get("cost_usd")).isNull();
    }
}
