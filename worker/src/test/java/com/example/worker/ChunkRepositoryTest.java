package com.example.worker;

import com.example.common.profile.LanguageProfiles;
import com.example.common.tenant.TenantTransactions;
import com.example.worker.chunking.ChunkRepository;
import com.example.worker.chunking.TextChunk;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Logs in as rag_app against the real schema, so row-level security and the per-model index both apply.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChunkRepositoryTest {

    private static final String INDEXED_MODEL = "nvidia/nemotron-3-embed-1b:free";

    @Autowired
    ChunkRepository chunks;
    @Autowired
    LanguageProfiles profiles;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TenantTransactions tenantTransactions;

    @Test
    void insert_savesTheChunkAndItsVector_withTheProfilesSearchConfig() {
        UUID tenantId = createTenant();
        UUID documentId = createDocument(tenantId);
        String tsConfig = profiles.get("tr").textSearchConfig();
        TextChunk chunk = new TextChunk(0, "Kurulum > Docker", "Yapılandırma dosyaları değişir", 7);

        tenantTransactions.run(tenantId, () ->
                chunks.insertChunkAndVectorRow(tenantId, documentId, chunk, tsConfig, INDEXED_MODEL, vector(2048)));

        Map<String, Object> row = tenantTransactions.run(tenantId, () -> jdbc.sql("""
                        select c.ordinal, c.content, c.heading_path, c.token_count, c.ts_config::text as ts_config,
                               c.tsv::text as tsv, e.model, vector_dims(e.embedding) as dims
                        from chunks c join chunk_embeddings e on e.chunk_id = c.id
                        where c.document_id = :id
                        """)
                .param("id", documentId)
                .query()
                .singleRow());
        assertThat(row.get("ordinal")).isEqualTo(0);
        assertThat(row.get("content")).isEqualTo(chunk.content());
        assertThat(row.get("heading_path")).isEqualTo(chunk.headingPath());
        assertThat(row.get("token_count")).isEqualTo(7);
        assertThat(row.get("ts_config")).isEqualTo(tsConfig);
        assertThat((String) row.get("tsv")).isNotBlank();
        assertThat(row.get("model")).isEqualTo(INDEXED_MODEL);
        assertThat(row.get("dims")).isEqualTo(2048);
    }

    @Test
    void vectorOfTheWrongSize_isRejected_andNoChunkIsLeft() {
        UUID tenantId = createTenant();
        UUID documentId = createDocument(tenantId);

        assertThatThrownBy(() -> tenantTransactions.run(tenantId, () -> chunks.insertChunkAndVectorRow(
                tenantId, documentId, new TextChunk(0, "", "metin", 1), "simple", INDEXED_MODEL, vector(3))));

        assertThat(chunkCount(tenantId, documentId)).isZero();
    }

    @Test
    void deleteByDocument_removesChunksAndVectors_ofThatDocumentOnly() {
        UUID tenantId = createTenant();
        UUID removed = createDocument(tenantId);
        UUID kept = createDocument(tenantId);
        tenantTransactions.run(tenantId, () -> {
            chunks.insertChunkAndVectorRow(tenantId, removed, new TextChunk(0, "", "bir", 1), "simple", INDEXED_MODEL, vector(2048));
            chunks.insertChunkAndVectorRow(tenantId, kept, new TextChunk(0, "", "iki", 1), "simple", INDEXED_MODEL, vector(2048));
        });

        tenantTransactions.run(tenantId, () -> chunks.deleteByDocument(removed));

        assertThat(chunkCount(tenantId, removed)).isZero();
        assertThat(chunkCount(tenantId, kept)).isEqualTo(1);
        long vectors = tenantTransactions.run(tenantId, () -> jdbc.sql("select count(*) from chunk_embeddings")
                .query(Long.class)
                .single());
        assertThat(vectors).isEqualTo(1);
    }

    @Test
    void anotherTenant_cannotInsertForThisTenant() {
        UUID owner = createTenant();
        UUID other = createTenant();
        UUID documentId = createDocument(owner);

        assertThatThrownBy(() -> tenantTransactions.run(other, () -> chunks.insertChunkAndVectorRow(
                owner, documentId, new TextChunk(0, "", "metin", 1), "simple", INDEXED_MODEL, vector(2048))));

        assertThat(chunkCount(owner, documentId)).isZero();
    }

    // Includes values whose text form has an exponent, like 1.0E-4
    private static float[] vector(int size) {
        float[] vector = new float[size];
        Arrays.fill(vector, 0.0001f);
        vector[0] = 0.5f;
        return vector;
    }

    private long chunkCount(UUID tenantId, UUID documentId) {
        return tenantTransactions.run(tenantId, () -> jdbc.sql("select count(*) from chunks where document_id = :id")
                .param("id", documentId)
                .query(Long.class)
                .single());
    }

    private UUID createTenant() {
        return jdbc.sql("insert into tenants (name, language_profile) values (:name, 'tr') returning id")
                .param("name", "t-" + UUID.randomUUID())
                .query(UUID.class)
                .single();
    }

    private UUID createDocument(UUID tenantId) {
        UUID documentId = UUID.randomUUID();
        tenantTransactions.run(tenantId, () -> jdbc.sql("""
                        insert into documents (id, tenant_id, file_name, content_type, size_bytes, checksum, s3_key, status)
                        values (:id, :tenantId, 'a', 'text/plain', 1, decode(md5(random()::text), 'hex'), 'k', 'processing')
                        """)
                .param("id", documentId)
                .param("tenantId", tenantId)
                .update());
        return documentId;
    }
}
