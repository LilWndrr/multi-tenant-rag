package com.example.api.retrieve;

import com.example.api.TestcontainersConfiguration;
import com.example.api.tenant.TenantService;
import com.example.common.model.ModelCall;
import com.example.common.model.ModelEmbedResponse;
import com.example.common.model.ModelGateway;
import com.example.common.model.Usage;
import com.example.common.profile.LanguageProfile;
import com.example.common.profile.LanguageProfiles;
import com.example.common.tenant.TenantTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The real query against pgvector, as rag_app under row-level security; only the model provider is replaced.
// Vectors are built from the first few axes, so the expected similarities are easy to work out by hand.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VectorRetrieverTest {

    @MockitoBean
    ModelGateway modelGateway;

    @Autowired
    VectorRetriever retriever;
    @Autowired
    TenantService tenantService;
    @Autowired
    TenantTransactions tenantTransactions;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    LanguageProfiles profiles;

    @Test
    void returnsTheNearestChunks_nearestFirst_withCosineSimilarity() {
        LanguageProfile profile = profiles.get("en");
        UUID tenant = newTenant();
        UUID document = newDocument(tenant, "guide.md");
        addChunk(tenant, document, 0, "far", "Setup", axis(1));                  // orthogonal: similarity 0
        addChunk(tenant, document, 1, "same", "Setup > Docker", axis(0));        // same direction: 1
        addChunk(tenant, document, 2, "between", "Usage", between(0, 1));        // 45 degrees: about 0.707
        questionVector(axis(0));

        List<RetrievedChunk> results = retriever.retrieve(tenant, profile, "how do I run it?", 20);

        assertThat(results).extracting(RetrievedChunk::content).containsExactly("same", "between", "far");
        assertThat(results).extracting(RetrievedChunk::score).satisfiesExactly(
                s -> assertThat(s).isCloseTo(1f, within(0.01f)),
                s -> assertThat(s).isCloseTo(0.707f, within(0.01f)),
                s -> assertThat(s).isCloseTo(0f, within(0.01f)));
        assertThat(results.getFirst()).satisfies(first -> {
            assertThat(first.fileName()).isEqualTo("guide.md");
            assertThat(first.headingPath()).isEqualTo("Setup > Docker");
            assertThat(first.documentId()).isEqualTo(document);
        });
    }

    @Test
    void returnsAtMostTheLimit() {
        UUID tenant = newTenant();
        UUID document = newDocument(tenant, "many.md");
        for (int i = 0; i < 25; i++) {
            addChunk(tenant, document, i, "chunk " + i, "", axis(i));
        }
        questionVector(axis(0));

        assertThat(retriever.retrieve(tenant, profiles.get("en"), "q", 20)).hasSize(20);
    }

    // The other tenant's chunks are all closer to the question, so a scan that stopped at its first candidates and
    // then applied row-level security would return nothing; the iterative scan keeps going until our rows turn up.
    @Test
    void onlyTheTenantsOwnChunks_evenWhenAnotherTenantHasCloserOnes() {
        UUID other = newTenant();
        UUID otherDocument = newDocument(other, "other.md");
        for (int i = 0; i < 60; i++) {
            addChunk(other, otherDocument, i, "other " + i, "", axis(0));
        }
        UUID tenant = newTenant();
        UUID document = newDocument(tenant, "mine.md");
        addChunk(tenant, document, 0, "mine a", "", between(0, 1));
        addChunk(tenant, document, 1, "mine b", "", axis(1));
        questionVector(axis(0));

        List<RetrievedChunk> results = retriever.retrieve(tenant, profiles.get("en"), "q", 20);

        assertThat(results).extracting(RetrievedChunk::content).containsExactly("mine a", "mine b");
    }

    @Test
    void embedsTheQuestionOnce_withTheProfilesModel_asAQueryEmbedding() {
        LanguageProfile profile = profiles.get("tr");
        UUID tenant = newTenant();
        questionVector(axis(0));

        retriever.retrieve(tenant, profile, "Kurulum nasıl yapılır?", 20);

        verify(modelGateway).embed(new ModelCall(tenant, "query-embedding", profile.embeddingModel()),
                List.of("Kurulum nasıl yapılır?"));
    }

    @Test
    void aVectorOfTheWrongSize_fails_ratherThanSearchingWithIt() {
        when(modelGateway.embed(any(), anyList())).thenReturn(response(new float[]{1f, 0f, 0f}));

        assertThatThrownBy(() -> retriever.retrieve(newTenant(), profiles.get("en"), "q", 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 2048 dimensions, got 3");
    }

    // With a handful of rows, reading the tenant's few vectors and sorting them is genuinely cheaper, so the planner
    // is told not to scan or sort: the index is then the only way to get rows in distance order. What this checks is
    // that the retriever's SQL has the shape the index can serve (the cast, the operator, the model literal). On a
    // real amount of data the planner picks the index by itself (see docs/decisions.md, Task 20).
    @Test
    void queryPlan_usesTheModelsHnswIndex() {
        LanguageProfile profile = profiles.get("en");
        UUID tenant = newTenant();
        addChunk(tenant, newDocument(tenant, "plan.md"), 0, "text", "", axis(0));

        List<String> plan = tenantTransactions.run(tenant, () -> {
            jdbc.sql("set local enable_seqscan = off").update();
            jdbc.sql("set local enable_sort = off").update();
            jdbc.sql("set local hnsw.iterative_scan = relaxed_order").update();
            return jdbc.sql("explain " + ChunkSearchRepository.nearestSql(profile.embeddingModel(), profile.embeddingDimensions()))
                    .param("query", Arrays.toString(axis(0)))
                    .param("limit", 20)
                    .query(String.class)
                    .list();
        });

        assertThat(String.join("\n", plan)).contains("Index Scan using chunk_embeddings_nemotron_hnsw");
    }

    @Test
    void aModelIdThatCouldBreakOutOfTheSql_isRejected() {
        assertThatThrownBy(() -> ChunkSearchRepository.nearestSql("x' or '1'='1", 2048))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- helpers

    private void questionVector(float[] vector) {
        when(modelGateway.embed(any(), anyList())).thenReturn(response(vector));
    }

    private static ModelEmbedResponse response(float[] vector) {
        return new ModelEmbedResponse(List.of(vector), "m", new Usage(0, 0, 0, null));
    }

    private static float[] axis(int i) {
        float[] v = new float[2048];
        v[i] = 1f;
        return v;
    }

    private static float[] between(int i, int j) {
        float[] v = new float[2048];
        v[i] = 1f;
        v[j] = 1f;
        return v;
    }

    private UUID newTenant() {
        return tenantService.create("t-" + UUID.randomUUID(), "en").id();
    }

    private UUID newDocument(UUID tenant, String fileName) {
        return tenantTransactions.run(tenant, () -> jdbc.sql("""
                        insert into documents (tenant_id, file_name, content_type, size_bytes, checksum, s3_key, status)
                        values (:tenant, :fileName, 'text/markdown', 10, decode(md5(random()::text), 'hex'), 'k', 'ready')
                        returning id
                        """)
                .param("tenant", tenant).param("fileName", fileName)
                .query(UUID.class).single());
    }

    private void addChunk(UUID tenant, UUID document, int ordinal, String content, String headingPath, float[] vector) {
        tenantTransactions.run(tenant, () -> {
            long chunkId = jdbc.sql("""
                            insert into chunks (tenant_id, document_id, ordinal, content, heading_path, token_count, ts_config)
                            values (:tenant, :document, :ordinal, :content, :heading, 1, 'simple') returning id
                            """)
                    .param("tenant", tenant).param("document", document).param("ordinal", ordinal)
                    .param("content", content).param("heading", headingPath)
                    .query(Long.class).single();
            jdbc.sql("""
                            insert into chunk_embeddings (chunk_id, tenant_id, model, embedding)
                            values (:chunk, :tenant, :model, cast(:vector as vector))
                            """)
                    .param("chunk", chunkId).param("tenant", tenant)
                    .param("model", profiles.get("en").embeddingModel())
                    .param("vector", Arrays.toString(vector))
                    .update();
            return null;
        });
    }
}
