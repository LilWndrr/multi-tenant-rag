package com.example.api.retrieve;

import com.example.api.TestcontainersConfiguration;
import com.example.api.tenant.TenantService;
import com.example.common.profile.LanguageProfile;
import com.example.common.profile.LanguageProfiles;
import com.example.common.tenant.TenantTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The real query against Postgres full-text search, as rag_app under row-level security. Chunks get the
// search config of their tenant's profile, as ingestion does; profile ids stand in for language names.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FullTextRetrieverTest {

    @Autowired
    FullTextRetriever retriever;
    @Autowired
    TenantService tenantService;
    @Autowired
    TenantTransactions tenantTransactions;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    LanguageProfiles profiles;

    @Test
    void anExactTerm_isFound_andChunksWithoutAnyOfTheWordsAreNot() {
        UUID tenant = newTenant("en");
        UUID doc = newDocument(tenant, "troubleshooting.md");
        addChunk(tenant, doc, 0, "en", "Restarting containers keeps failing with CrashLoopBackOff after the upgrade.");
        addChunk(tenant, doc, 1, "en", "Volumes keep data across container restarts.");
        addChunk(tenant, doc, 2, "en", "Services expose a set of endpoints behind one address.");

        List<RetrievedChunk> results = retrieve(tenant, "en", "Why do I see CrashLoopBackOff?");

        assertThat(results).extracting(RetrievedChunk::content)
                .containsExactly("Restarting containers keeps failing with CrashLoopBackOff after the upgrade.");
        assertThat(results.getFirst().fileName()).isEqualTo("troubleshooting.md");
    }

    // with AND, no chunk would match: none has both words
    @Test
    void anyWordIsEnough_andChunksWithMoreOfTheWordsComeFirst() {
        UUID tenant = newTenant("en");
        UUID doc = newDocument(tenant, "a.md");
        addChunk(tenant, doc, 0, "en", "The kubelet runs on every node.");
        addChunk(tenant, doc, 1, "en", "Eviction thresholds tell the kubelet when to evict pods.");
        addChunk(tenant, doc, 2, "en", "Pods are scheduled onto nodes.");

        List<RetrievedChunk> results = retrieve(tenant, "en", "how does the kubelet decide on eviction thresholds and other limits");

        assertThat(results).extracting(RetrievedChunk::content).first()
                .isEqualTo("Eviction thresholds tell the kubelet when to evict pods.");
        assertThat(results).extracting(RetrievedChunk::content).contains("The kubelet runs on every node.");
        assertThat(results).extracting(RetrievedChunk::score).isSortedAccordingTo((a, b) -> Float.compare(b, a));
        assertThat(results).allSatisfy(r -> assertThat(r.score()).isBetween(0f, 1f));
    }

    // The case Postgres's own ranking gets wrong: the chunks full of a common word outrank the one with the rare word
    // the question is about. Weighting by rarity puts the rare one first (docs/decisions.md, Task 21).
    @Test
    void aRareWord_outweighsACommonOne() {
        UUID tenant = newTenant("en");
        UUID doc = newDocument(tenant, "containers.md");
        for (int i = 0; i < 12; i++) {
            addChunk(tenant, doc, i, "en", "A container runs in a pod; container images build the container " + i + ".");
        }
        addChunk(tenant, doc, 12, "en", "The process was OOMKilled because it used more memory than its limit.");

        List<RetrievedChunk> results = retrieve(tenant, "en", "What does OOMKilled mean for my container?");

        assertThat(results.getFirst().content()).contains("OOMKilled");
    }

    @Test
    void anExcludedWord_excludesTheChunk_evenWhenOtherWordsMatch() {
        UUID tenant = newTenant("en");
        UUID doc = newDocument(tenant, "a.md");
        addChunk(tenant, doc, 0, "en", "A pod that keeps restarting.");
        addChunk(tenant, doc, 1, "en", "A pod that is stuck pending.");

        assertThat(retrieve(tenant, "en", "pod stuck -restarting")).extracting(RetrievedChunk::content)
                .containsExactly("A pod that is stuck pending.");
    }

    @Test
    void wordsAreStemmedWithTheProfilesConfig() {
        UUID tenant = newTenant("tr");
        UUID doc = newDocument(tenant, "kitap.md");
        addChunk(tenant, doc, 0, "tr", "Kitaplar rafta duruyor.");
        addChunk(tenant, doc, 1, "tr", "Masa pencerenin önünde.");

        assertThat(retrieve(tenant, "tr", "kitabı nerede bulurum")).extracting(RetrievedChunk::content)
                .containsExactly("Kitaplar rafta duruyor.");
    }

    @Test
    void yearsAndVersionsAreSearchableTerms() {
        UUID tenant = newTenant("tr");
        UUID doc = newDocument(tenant, "asteroit.md");
        addChunk(tenant, doc, 0, "tr", "Asteroit 29 Kasım 1924 tarihinde keşfedildi.");
        addChunk(tenant, doc, 1, "tr", "Gözlemevi 1898 yılında kuruldu.");
        UUID other = newTenant("en");
        UUID otherDoc = newDocument(other, "notes.md");
        addChunk(other, otherDoc, 0, "en", "The fix shipped in 1.31.2 together with the scheduler change.");

        assertThat(retrieve(tenant, "tr", "1924 yılında ne oldu?")).extracting(RetrievedChunk::content).first()
                .isEqualTo("Asteroit 29 Kasım 1924 tarihinde keşfedildi.");
        assertThat(retrieve(other, "en", "what changed in 1.31.2")).extracting(RetrievedChunk::content)
                .containsExactly("The fix shipped in 1.31.2 together with the scheduler change.");
    }

    @Test
    void aCapitalDottedI_matchesTheLowercaseLetter() {
        UUID tenant = newTenant("tr");
        addChunk(tenant, newDocument(tenant, "şehir.md"), 0, "tr", "İstanbul büyük bir şehirdir.");

        assertThat(retrieve(tenant, "tr", "istanbul")).hasSize(1);
    }

    @Test
    void onlyTheTenantsOwnChunks() {
        UUID other = newTenant("en");
        addChunk(other, newDocument(other, "theirs.md"), 0, "en", "CrashLoopBackOff explained in detail.");
        UUID tenant = newTenant("en");
        addChunk(tenant, newDocument(tenant, "mine.md"), 0, "en", "Our own note about CrashLoopBackOff.");

        assertThat(retrieve(tenant, "en", "CrashLoopBackOff")).extracting(RetrievedChunk::content)
                .containsExactly("Our own note about CrashLoopBackOff.");
    }

    @Test
    void returnsAtMostTheLimit() {
        UUID tenant = newTenant("en");
        UUID doc = newDocument(tenant, "many.md");
        for (int i = 0; i < 25; i++) {
            addChunk(tenant, doc, i, "en", "Namespace note number " + i);
        }

        assertThat(retriever.retrieve(tenant, profiles.get("en"), "namespace", 20)).hasSize(20);
    }

    @Test
    void aQuestionOfOnlyStopWords_findsNothing() {
        UUID tenant = newTenant("en");
        addChunk(tenant, newDocument(tenant, "a.md"), 0, "en", "What is it, and why is it here?");

        assertThat(retrieve(tenant, "en", "what is it")).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private List<RetrievedChunk> retrieve(UUID tenant, String profile, String question) {
        return retriever.retrieve(tenant, profiles.get(profile), question, 20);
    }

    private UUID newTenant(String profile) {
        return tenantService.create("t-" + UUID.randomUUID(), profile).id();
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

    private void addChunk(UUID tenant, UUID document, int ordinal, String profile, String content) {
        LanguageProfile p = profiles.get(profile);
        tenantTransactions.run(tenant, () -> jdbc.sql("""
                        insert into chunks (tenant_id, document_id, ordinal, content, token_count, ts_config)
                        values (:tenant, :document, :ordinal, :content, 1, cast(:config as regconfig))
                        """)
                .param("tenant", tenant).param("document", document).param("ordinal", ordinal)
                .param("content", content).param("config", p.textSearchConfig())
                .update());
    }
}
