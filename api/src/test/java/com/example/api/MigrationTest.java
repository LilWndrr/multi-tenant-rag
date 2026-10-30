package com.example.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MigrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void latestAppliedVersionIsV1() {
        String version = jdbc.sql("""
                        SELECT version FROM flyway_schema_history
                        WHERE success ORDER BY installed_rank DESC LIMIT 1
                        """)
                .query(String.class)
                .single();

        assertThat(version).isEqualTo("1");
    }

    @Test
    void createsAllV1Tables() {
        var tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public'
                        """)
                .query(String.class)
                .list();

        assertThat(tables).contains("tenants", "api_keys", "model_calls");
    }

    @Test
    void enablesVectorExtension() {
        Long count = jdbc.sql("SELECT count(*) FROM pg_extension WHERE extname = 'vector'")
                .query(Long.class)
                .single();

        assertThat(count).isEqualTo(1);
    }

    @Test
    @Transactional
    void deletingTenantRemovesKeysButKeepsModelCalls() {
        UUID tenantId = jdbc.sql("""
                        INSERT INTO tenants (name, language_profile)
                        VALUES ('cascade-test', 'en') RETURNING id
                        """)
                .query(UUID.class)
                .single();

        jdbc.sql("""
                        INSERT INTO api_keys (tenant_id, prefix, key_hash, name)
                        VALUES (:tenantId, 'rag_test', decode('aa', 'hex'), 'test key')
                        """)
                .param("tenantId", tenantId)
                .update();

        Long callId = jdbc.sql("""
                        INSERT INTO model_calls (tenant_id, purpose, provider, model,
                                                 input_tokens, output_tokens, latency_ms, status)
                        VALUES (:tenantId, 'chat', 'openrouter', 'test-model', 10, 5, 100, 'ok')
                        RETURNING id
                        """)
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();

        jdbc.sql("DELETE FROM tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .update();

        Long keysLeft = jdbc.sql("SELECT count(*) FROM api_keys WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
        Long orphanedCalls = jdbc.sql("SELECT count(*) FROM model_calls WHERE id = :id AND tenant_id IS NULL")
                .param("id", callId)
                .query(Long.class)
                .single();

        assertThat(keysLeft).isZero();
        assertThat(orphanedCalls).isEqualTo(1);
    }
}