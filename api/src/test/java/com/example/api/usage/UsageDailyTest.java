package com.example.api.usage;

import com.example.api.TestcontainersConfiguration;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The trigger on model_calls, as rag_app: every logged call is added to its tenant's day, purpose and model
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UsageDailyTest {

    @Autowired
    ModelCallLogger logger;
    @Autowired
    TenantService tenantService;
    @Autowired
    UsageRepository usageRepository;
    @Autowired
    JdbcClient jdbc;

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private UUID newTenant() {
        return tenantService.create("t-" + UUID.randomUUID(), "en").id();
    }

    private void call(UUID tenant, String purpose, String model, String status, int in, int out, int cached, String cost) {
        logger.createModelCall(new ModelCall(tenant, purpose, model), "openrouter", model, status,
                new Usage(in, out, cached, cost == null ? null : new BigDecimal(cost)), 100);
    }

    @Test
    void callsOfOneDayPurposeAndModel_areSummedIntoOneRow() {
        UUID tenant = newTenant();
        call(tenant, "answer", "m/chat", "ok", 2600, 80, 0, "0.00040000");
        call(tenant, "answer", "m/chat", "ok", 2600, 70, 2560, "0.00005000");
        call(tenant, "answer", "m/chat", "error", 0, 0, 0, null);

        List<UsageReport.Row> rows = usageRepository.forTenant(tenant, TODAY, TODAY);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.day()).isEqualTo(TODAY);
            assertThat(row.purpose()).isEqualTo("answer");
            assertThat(row.model()).isEqualTo("m/chat");
            assertThat(row.requests()).isEqualTo(3);
            assertThat(row.errors()).isEqualTo(1);
            assertThat(row.inputTokens()).isEqualTo(5200);
            assertThat(row.outputTokens()).isEqualTo(150);
            assertThat(row.cachedTokens()).isEqualTo(2560);
            assertThat(row.costUsd()).isEqualByComparingTo("0.00045");   // the unknown cost counts as 0
        });
    }

    @Test
    void eachPurposeAndModel_getsItsOwnRow_andTenantsDontMix() {
        UUID tenant = newTenant();
        UUID other = newTenant();
        call(tenant, "query-embedding", "m/embed", "ok", 12, 0, 0, "0");
        call(tenant, "rerank", "m/rerank", "ok", 3000, 0, 0, "0.00006");
        call(tenant, "answer", "m/chat", "ok", 2600, 80, 0, "0.0004");
        call(other, "answer", "m/chat", "ok", 1, 1, 0, "1");

        assertThat(usageRepository.forTenant(tenant, TODAY, TODAY))
                .extracting(UsageReport.Row::purpose, UsageReport.Row::requests)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("answer", 1),
                        org.assertj.core.groups.Tuple.tuple("query-embedding", 1),
                        org.assertj.core.groups.Tuple.tuple("rerank", 1));
        assertThat(usageRepository.forTenant(other, TODAY, TODAY)).singleElement()
                .satisfies(row -> assertThat(row.costUsd()).isEqualByComparingTo("1"));
    }

    // calls for no tenant (tests, the evaluation harness) are still counted, so the platform's daily spend is complete
    @Test
    void callsWithoutATenant_areCountedTogether() {
        String model = "m/" + UUID.randomUUID();
        call(null, "answer", model, "ok", 10, 5, 0, "0.001");
        call(null, "answer", model, "ok", 10, 5, 0, "0.001");

        Map<String, Object> row = jdbc.sql("select requests, cost_usd from usage_daily where tenant_id is null and model = :m")
                .param("m", model).query().singleRow();

        assertThat(row.get("requests")).isEqualTo(2);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.002");
    }

    // a deleted tenant's past usage stays (model_calls keeps the rows with tenant_id set to null)
    @Test
    void deletingATenant_keepsItsUsage() {
        UUID tenant = newTenant();
        call(tenant, "answer", "m/chat", "ok", 10, 5, 0, "0.001");

        jdbc.sql("delete from tenants where id = :id").param("id", tenant).update();

        assertThat(usageRepository.forTenant(tenant, TODAY, TODAY)).singleElement()
                .satisfies(row -> assertThat(row.requests()).isEqualTo(1));
    }
}
