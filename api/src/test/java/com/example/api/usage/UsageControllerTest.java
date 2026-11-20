package com.example.api.usage;

import com.example.api.TestcontainersConfiguration;
import com.example.api.apiKey.ApiKeyService;
import com.example.api.tenant.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UsageControllerTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired
    MockMvc mvc;
    @Autowired
    TenantService tenantService;
    @Autowired
    ApiKeyService apiKeyService;
    @Autowired
    JdbcClient jdbc;

    private void usage(UUID tenant, LocalDate day, String purpose, int requests, String cost) {
        jdbc.sql("""
                        insert into usage_daily (tenant_id, day, purpose, model, requests, errors,
                                                 input_tokens, output_tokens, cached_tokens, cost_usd)
                        values (:tenant, :day, :purpose, 'm', :requests, 0, 100, 10, 50, cast(:cost as numeric))
                        """)
                .param("tenant", tenant).param("day", day).param("purpose", purpose)
                .param("requests", requests).param("cost", cost).update();
    }

    @Test
    void theLast30Days_ofTheKeysTenantOnly_withTotals() throws Exception {
        UUID tenant = tenantService.create("t-" + UUID.randomUUID(), "en").id();
        UUID other = tenantService.create("t-" + UUID.randomUUID(), "en").id();
        String key = apiKeyService.create(tenant, "website").key();
        usage(tenant, TODAY, "answer", 3, "0.0012");
        usage(tenant, TODAY.minusDays(29), "rerank", 2, "0.0001");
        usage(tenant, TODAY.minusDays(30), "answer", 99, "9");   // one day too old
        usage(other, TODAY, "answer", 50, "5");

        mvc.perform(get("/v1/usage").header("Authorization", "Bearer " + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(TODAY.minusDays(29).toString()))
                .andExpect(jsonPath("$.to").value(TODAY.toString()))
                .andExpect(jsonPath("$.days", hasSize(2)))
                .andExpect(jsonPath("$.days[0].purpose").value("rerank"))
                .andExpect(jsonPath("$.days[1].requests").value(3))
                .andExpect(jsonPath("$.total.requests").value(5))
                .andExpect(jsonPath("$.total.cachedTokens").value(100))
                .andExpect(jsonPath("$.total.costUsd").value(closeTo(0.0013, 1e-9)));
    }

    @Test
    void aChosenRange_bothDaysIncluded() throws Exception {
        UUID tenant = tenantService.create("t-" + UUID.randomUUID(), "en").id();
        String key = apiKeyService.create(tenant, "website").key();
        usage(tenant, LocalDate.of(2026, 1, 1), "answer", 1, "0.1");
        usage(tenant, LocalDate.of(2026, 1, 31), "answer", 1, "0.1");
        usage(tenant, LocalDate.of(2026, 2, 1), "answer", 1, "0.1");

        mvc.perform(get("/v1/usage?from=2026-01-01&to=2026-01-31").header("Authorization", "Bearer " + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(2)));
    }

    @Test
    void aRangeBackwards_orOverAYear_orNotADate_400() throws Exception {
        String key = apiKeyService.create(tenantService.create("t-" + UUID.randomUUID(), "en").id(), "website").key();

        for (String query : new String[]{"from=2026-02-01&to=2026-01-01", "from=2025-01-01&to=2026-01-02", "from=yesterday"}) {
            mvc.perform(get("/v1/usage?" + query).header("Authorization", "Bearer " + key))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void withoutAKey_401() throws Exception {
        mvc.perform(get("/v1/usage")).andExpect(status().isUnauthorized());
    }
}
