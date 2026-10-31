package com.example.api;

import com.example.api.helpers.ApiKeyGenerator;
import com.example.api.tenant.TenantService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public class ApiKeyAdminTest {
    private static final String ADMIN = "Bearer test-token";

    @Autowired
    MockMvc mvc;
    @Autowired
    TenantService tenantService;
    @Autowired
    JdbcClient jdbc;

    private UUID newTenant() {
        // unique name: the DB keeps rows between tests, and names are unique
        return tenantService.create("t-" + UUID.randomUUID(), "en").id();
    }

    private String createKey(UUID tenantId, String name) throws Exception {
        return mvc.perform(post("/admin/tenants/{tenantId}/api-keys", tenantId)
                        .header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private UUID keyId(String createBody) {
        return UUID.fromString(JsonPath.read(createBody, "$.id"));
    }

    @Test
    void createApiKey_returnsKeyOnce_andStoresOnlyHash() throws Exception {
        UUID tenantId = newTenant();

        String body = mvc.perform(post("/admin/tenants/{tenantId}/api-keys", tenantId)
                        .header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "website"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.key").value(startsWith("rk_")))
                .andExpect(jsonPath("$.name").value("website"))
                .andReturn().getResponse().getContentAsString();

        String key = JsonPath.read(body, "$.key");

        // the full key must not be anywhere in the table
        Integer matches = jdbc.sql("select count(*) from api_keys where prefix = :k or name = :k")
                .param("k", key).query(Integer.class).single();
        assertThat(matches).isZero();

        // what is stored is exactly the SHA-256 of the key
        byte[] stored = jdbc.sql("select key_hash from api_keys where tenant_id = :t")
                .param("t", tenantId).query(byte[].class).single();
        assertThat(stored).isEqualTo(ApiKeyGenerator.hash(key));
    }

    @Test
    void createApiKey_withoutToken_returns401() throws Exception {
        mvc.perform(post("/admin/tenants/{tenantId}/api-keys", newTenant())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "website"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createApiKey_withBlankName_returns400() throws Exception {
        mvc.perform(post("/admin/tenants/{tenantId}/api-keys", newTenant())
                        .header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createApiKey_forUnknownTenant_returns404() throws Exception {
        mvc.perform(post("/admin/tenants/{tenantId}/api-keys", UUID.randomUUID())
                        .header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "website"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void revoke_setsRevokedAt_andIsIdempotent() throws Exception {
        UUID tenantId = newTenant();
        UUID keyId = keyId(createKey(tenantId, "website"));

        mvc.perform(post("/admin/tenants/{tenantId}/api-keys/{keyId}/revoke", tenantId, keyId)
                        .header("Authorization", ADMIN))
                .andExpect(status().isNoContent());

        Boolean revoked = jdbc.sql("select revoked_at is not null from api_keys where id = :id")
                .param("id", keyId).query(Boolean.class).single();
        assertThat(revoked).isTrue();

        // revoking again changes nothing and still succeeds
        mvc.perform(post("/admin/tenants/{tenantId}/api-keys/{keyId}/revoke", tenantId, keyId)
                        .header("Authorization", ADMIN))
                .andExpect(status().isNoContent());
    }

    @Test
    void revoke_unknownKey_returns404() throws Exception {
        mvc.perform(post("/admin/tenants/{tenantId}/api-keys/{keyId}/revoke", newTenant(), UUID.randomUUID())
                        .header("Authorization", ADMIN))
                .andExpect(status().isNotFound());
    }

    @Test
    void revoke_keyOfAnotherTenant_returns404_andKeyStaysActive() throws Exception {
        UUID owner = newTenant();
        UUID other = newTenant();
        UUID keyId = keyId(createKey(owner, "website"));

        mvc.perform(post("/admin/tenants/{tenantId}/api-keys/{keyId}/revoke", other, keyId)
                        .header("Authorization", ADMIN))
                .andExpect(status().isNotFound());

        Boolean revoked = jdbc.sql("select revoked_at is not null from api_keys where id = :id")
                .param("id", keyId).query(Boolean.class).single();
        assertThat(revoked).isFalse();
    }

    @Test
    void listKeys_returnsOnlyThisTenantsKeys_withoutFullKey() throws Exception {
        UUID tenantId = newTenant();
        createKey(tenantId, "website");
        createKey(newTenant(), "other-tenant-key");

        mvc.perform(get("/admin/tenants/{tenantId}/api-keys", tenantId)
                        .header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("website"))
                .andExpect(jsonPath("$[0].prefix").value(startsWith("rk_")))
                .andExpect(jsonPath("$[0].key").doesNotExist());
    }
}
