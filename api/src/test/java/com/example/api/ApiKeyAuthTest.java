package com.example.api;

import com.example.api.apiKey.ApiKeyService;
import com.example.api.apiKey.CreatedApiKey;
import com.example.api.tenant.Tenant;
import com.example.api.tenant.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public class ApiKeyAuthTest {
    private static final String ADMIN = "Bearer test-token";
    private static final String PREFIX = "Bearer ";
    private static final String CONTEXT = "/v1/context";

    @Autowired
    MockMvc mvc;
    @Autowired
    ApiKeyService apiKeyService;
    @Autowired
    TenantService tenantService;

    private Tenant createTenant(String languageProfile) {
        return tenantService.create("t-" + UUID.randomUUID(), languageProfile);
    }

    private CreatedApiKey createKey(UUID tenantId) {
        return apiKeyService.create(tenantId, "test");
    }

    @Test
    public void validKey_returns200AndItsTenant() throws Exception {
        Tenant tenant = createTenant("tr");
        String apiKey = createKey(tenant.id()).key();

        mvc.perform(get(CONTEXT).header("Authorization", PREFIX + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.id().toString()))
                .andExpect(jsonPath("$.languageProfile").value("tr"));
    }

    @Test
    public void noHeader_returns401WithBearerChallenge() throws Exception {
        mvc.perform(get(CONTEXT))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    public void unknownKey_returns401() throws Exception {
        mvc.perform(get(CONTEXT).header("Authorization", PREFIX + "rk_doesnotexist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void revokedKey_returns401() throws Exception {
        Tenant tenant = createTenant("en");
        CreatedApiKey created = createKey(tenant.id());
        apiKeyService.revoke(created.id(), tenant.id());

        mvc.perform(get(CONTEXT).header("Authorization", PREFIX + created.key()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void wrongScheme_returns401() throws Exception {
        mvc.perform(get(CONTEXT).header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void adminToken_isNotAnApiKey_returns401() throws Exception {
        mvc.perform(get(CONTEXT).header("Authorization", ADMIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void twoTenants_eachKeyResolvesToItsOwnTenant() throws Exception {
        Tenant first = createTenant("en");
        Tenant second = createTenant("tr");
        String firstKey = createKey(first.id()).key();
        String secondKey = createKey(second.id()).key();

        mvc.perform(get(CONTEXT).header("Authorization", PREFIX + firstKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(first.id().toString()))
                .andExpect(jsonPath("$.languageProfile").value("en"));

        mvc.perform(get(CONTEXT).header("Authorization", PREFIX + secondKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(second.id().toString()))
                .andExpect(jsonPath("$.languageProfile").value("tr"));
    }
}
