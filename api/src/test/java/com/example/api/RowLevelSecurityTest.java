package com.example.api;

import com.example.api.apiKey.ApiKeyService;
import com.example.api.tenant.TenantService;
import com.example.common.tenant.TenantTransactions;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Not @Transactional: every step commits, as in production, so the setting must also be gone after commit.
// Each test makes its own tenants, so rows from other tests never change the counts.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RowLevelSecurityTest {

    private static final List<String> CONTENT_TABLES = List.of("documents", "chunks", "chunk_embeddings", "ingestion_jobs");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TenantTransactions tenantTransactions;
    @Autowired
    TenantService tenantService;
    @Autowired
    ApiKeyService apiKeyService;

    @Test
    void appUser_isNotSuperuser_andCannotBypassRowLevelSecurity() {
        var role = jdbc.sql("select rolname, rolsuper, rolbypassrls from pg_roles where rolname = current_user")
                .query((rs, n) -> List.<Object>of(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)))
                .single();

        assertThat(role).containsExactly(TestcontainersConfiguration.APP_USER, false, false);
    }

    @Test
    void everyContentTable_hasRowLevelSecurityEnabledAndForced() {
        List<String> protectedTables = jdbc.sql("""
                        select relname from pg_class
                        where relnamespace = 'public'::regnamespace and relrowsecurity and relforcerowsecurity
                        """)
                .query(String.class)
                .list();

        assertThat(protectedTables).containsAll(CONTENT_TABLES);
    }

    @Test
    void tenantAsKey_readsZeroRowsOfTenantB_evenWithoutATenantFilter() throws Exception {
        UUID tenantA = newTenant();
        UUID tenantB = newTenant();
        UUID documentB = insertDocumentWithChunk(tenantB);
        String keyA = apiKeyService.create(tenantA, "rls-test").key();

        // the tenant id the auth filter resolves from A's key, exactly as a request would see it
        String body = mvc.perform(get("/v1/context").header("Authorization", "Bearer " + keyA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID tenantFromKey = UUID.fromString(JsonPath.read(body, "$.tenantId"));
        assertThat(tenantFromKey).isEqualTo(tenantA);

        // no "where tenant_id = ..." anywhere: only the policy stands between A and B's rows
        assertThat(tenantTransactions.run(tenantFromKey, () -> count("select count(*) from documents"))).isZero();
        assertThat(tenantTransactions.run(tenantFromKey, () -> count("select count(*) from chunks"))).isZero();
        assertThat(tenantTransactions.run(tenantFromKey, () -> countById("select count(*) from documents where id = :id", documentB))).isZero();

        // B still sees its own rows, so the zero above is the policy and not a missing insert
        assertThat(tenantTransactions.run(tenantB, () -> count("select count(*) from documents"))).isEqualTo(1);
        assertThat(tenantTransactions.run(tenantB, () -> count("select count(*) from chunks"))).isEqualTo(1);
    }

    @Test
    void withNoTenantSet_readsNothing_andCannotInsert() {
        UUID tenantB = newTenant();
        UUID documentB = insertDocumentWithChunk(tenantB);

        assertThat(countById("select count(*) from documents where id = :id", documentB)).isZero();
        assertThatThrownBy(() -> insertDocument(tenantB))
                .isInstanceOf(DataAccessException.class)
                .rootCause()   // Postgres's own message; Spring's wrapper only says "bad SQL grammar"
                .hasMessageContaining("violates row-level security policy");
    }

    @Test
    void insertingARowForAnotherTenant_isRejected() {
        UUID tenantA = newTenant();
        UUID tenantB = newTenant();

        assertThatThrownBy(() -> tenantTransactions.run(tenantA, () -> insertDocument(tenantB)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()   // Postgres's own message; Spring's wrapper only says "bad SQL grammar"
                .hasMessageContaining("violates row-level security policy");
    }

    @Test
    void updateAndDelete_cannotReachAnotherTenantsRows() {
        UUID tenantA = newTenant();
        UUID tenantB = newTenant();
        UUID documentB = insertDocumentWithChunk(tenantB);

        int updated = tenantTransactions.run(tenantA, () -> jdbc.sql("update documents set status = 'failed' where id = :id")
                .param("id", documentB).update());
        int deleted = tenantTransactions.run(tenantA, () -> jdbc.sql("delete from documents where id = :id")
                .param("id", documentB).update());

        assertThat(updated).isZero();
        assertThat(deleted).isZero();
        String status = tenantTransactions.run(tenantB, () -> jdbc.sql("select status from documents where id = :id")
                .param("id", documentB).query(String.class).single());
        assertThat(status).isEqualTo("queued");
    }

    @Test
    void tenantSetting_isGoneAfterItsTransactionCommits() {
        UUID tenantB = newTenant();
        UUID documentB = insertDocumentWithChunk(tenantB);
        tenantTransactions.run(tenantB, () -> count("select count(*) from documents"));

        // a pooled connection goes back with nothing set, so the next request can't inherit B
        String setting = jdbc.sql("select coalesce(current_setting('app.tenant_id', true), '')")
                .query(String.class)
                .single();
        assertThat(setting).isEmpty();
        assertThat(countById("select count(*) from documents where id = :id", documentB)).isZero();
    }

    private UUID newTenant() {
        return tenantService.create("rls-" + UUID.randomUUID(), "tr").id();
    }

    private UUID insertDocumentWithChunk(UUID tenantId) {
        return tenantTransactions.run(tenantId, () -> {
            UUID documentId = insertDocument(tenantId);
            jdbc.sql("""
                            insert into chunks (tenant_id, document_id, ordinal, content, token_count, ts_config)
                            values (:tenantId, :documentId, 0, 'text', 1, 'simple')
                            """)
                    .param("tenantId", tenantId)
                    .param("documentId", documentId)
                    .update();
            return documentId;
        });
    }

    private UUID insertDocument(UUID tenantId) {
        return jdbc.sql("""
                        insert into documents (tenant_id, file_name, content_type, size_bytes, checksum, s3_key, status)
                        values (:tenantId, 'a.md', 'text/markdown', 10, decode(md5(random()::text), 'hex'), 'k', 'queued')
                        returning id
                        """)
                .param("tenantId", tenantId)
                .query(UUID.class)
                .single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long countById(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }
}
