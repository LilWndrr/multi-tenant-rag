package com.example.api;

import com.example.common.profile.LanguageProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MigrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    LanguageProfiles profiles;

    @Test
    void latestAppliedVersionIsV4() {
        String version = jdbc.sql("""
                        SELECT version FROM flyway_schema_history
                        WHERE success ORDER BY installed_rank DESC LIMIT 1
                        """)
                .query(String.class)
                .single();

        assertThat(version).isEqualTo("4");
    }

    @Test
    void createsAllTables() {
        var tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public'
                        """)
                .query(String.class)
                .list();

        assertThat(tables).contains("tenants", "api_keys", "model_calls",
                "documents", "chunks", "chunk_embeddings", "ingestion_jobs");
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

    // ts_config comes from the profiles, as ingestion will do it; no language name appears here
    @Test
    @Transactional
    void chunkTsvectorIsStemmedWithItsOwnTextSearchConfig() {
        UUID tenantId = insertTenant("stems-test");
        actAs(tenantId);
        UUID documentId = insertDocument(tenantId, "aa");

        String text = "kitaplar okudum";
        String tr = insertChunk(tenantId, documentId, 0, text, profiles.get("tr").textSearchConfig());
        String en = insertChunk(tenantId, documentId, 1, text, profiles.get("en").textSearchConfig());

        assertThat(tr).contains("'kitap'").doesNotContain("'kitaplar'");
        assertThat(en).contains("'kitaplar'");
    }

    @Test
    @Transactional
    void sameChecksum_isAllowedForAnotherTenant_butNotTwiceForOne() {
        UUID tenantA = insertTenant("checksum-a");
        UUID tenantB = insertTenant("checksum-b");
        actAs(tenantA);
        insertDocument(tenantA, "bb");

        actAs(tenantB);
        insertDocument(tenantB, "bb");   // another tenant must not learn that A has this file

        actAs(tenantA);
        assertThatThrownBy(() -> insertDocument(tenantA, "bb"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @Transactional
    void deletingDocument_removesItsChunksEmbeddingsAndJobs() {
        UUID tenantId = insertTenant("doc-cascade");
        actAs(tenantId);
        UUID documentId = insertDocument(tenantId, "cc");
        long chunkId = insertChunkId(tenantId, documentId);
        // the vector column has no dimension: two models, two lengths, same chunk
        insertEmbedding(chunkId, tenantId, "bge-m3", 1024);
        insertEmbedding(chunkId, tenantId, "some/other-model", 3);
        jdbc.sql("""
                        INSERT INTO ingestion_jobs (tenant_id, document_id, status)
                        VALUES (:tenantId, :documentId, 'running')
                        """)
                .param("tenantId", tenantId)
                .param("documentId", documentId)
                .update();

        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", documentId).update();

        assertThat(count("SELECT count(*) FROM chunks WHERE document_id = :id", documentId)).isZero();
        assertThat(count("SELECT count(*) FROM chunk_embeddings WHERE chunk_id = :id", chunkId)).isZero();
        assertThat(count("SELECT count(*) FROM ingestion_jobs WHERE document_id = :id", documentId)).isZero();
    }

    @Test
    @Transactional
    void bgeM3Embedding_withWrongDimension_isRejectedByItsIndex() {
        UUID tenantId = insertTenant("dimension-check");
        actAs(tenantId);
        long chunkId = insertChunkId(tenantId, insertDocument(tenantId, "dd"));

        // the partial HNSW index casts bge-m3 rows to vector(1024), so a 3-dim vector cannot be stored
        assertThatThrownBy(() -> insertEmbedding(chunkId, tenantId, "bge-m3", 3))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("1024");
    }

    // content tables have row-level security (V3); the test transaction acts as this tenant from here on
    private void actAs(UUID tenantId) {
        jdbc.sql("select set_config('app.tenant_id', :tenantId, true)")
                .param("tenantId", tenantId.toString())
                .query(String.class)
                .single();
    }

    private UUID insertTenant(String name) {
        return jdbc.sql("INSERT INTO tenants (name, language_profile) VALUES (:name, 'tr') RETURNING id")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    private UUID insertDocument(UUID tenantId, String checksumHex) {
        return jdbc.sql("""
                        INSERT INTO documents (tenant_id, file_name, content_type, size_bytes, checksum, s3_key, status)
                        VALUES (:tenantId, 'a.md', 'text/markdown', 10, decode(:checksum, 'hex'), 'k', 'queued')
                        RETURNING id
                        """)
                .param("tenantId", tenantId)
                .param("checksum", checksumHex)
                .query(UUID.class)
                .single();
    }

    private String insertChunk(UUID tenantId, UUID documentId, int ordinal, String content, String tsConfig) {
        return jdbc.sql("""
                        INSERT INTO chunks (tenant_id, document_id, ordinal, content, token_count, ts_config)
                        VALUES (:tenantId, :documentId, :ordinal, :content, 2, cast(:tsConfig as regconfig))
                        RETURNING tsv::text
                        """)
                .param("tenantId", tenantId)
                .param("documentId", documentId)
                .param("ordinal", ordinal)
                .param("content", content)
                .param("tsConfig", tsConfig)
                .query(String.class)
                .single();
    }

    private long insertChunkId(UUID tenantId, UUID documentId) {
        return jdbc.sql("""
                        INSERT INTO chunks (tenant_id, document_id, ordinal, content, token_count, ts_config)
                        VALUES (:tenantId, :documentId, 0, 'text', 1, 'simple')
                        RETURNING id
                        """)
                .param("tenantId", tenantId)
                .param("documentId", documentId)
                .query(Long.class)
                .single();
    }

    private void insertEmbedding(long chunkId, UUID tenantId, String model, int dimensions) {
        String vector = "[" + String.join(",", Collections.nCopies(dimensions, "0.1")) + "]";
        jdbc.sql("""
                        INSERT INTO chunk_embeddings (chunk_id, tenant_id, model, embedding)
                        VALUES (:chunkId, :tenantId, :model, cast(:vector as vector))
                        """)
                .param("chunkId", chunkId)
                .param("tenantId", tenantId)
                .param("model", model)
                .param("vector", vector)
                .update();
    }

    private long count(String sql, Object id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }
}
