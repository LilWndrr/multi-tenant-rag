package com.example.api.chat;

import com.example.api.TestcontainersConfiguration;
import com.example.api.tenant.TenantService;
import com.example.common.tenant.TenantTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The V7 trigger, as rag_app under row-level security: a tenant's docs version changes with its chunks, and only then
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocsVersionTest {

    @Autowired
    DocsVersions docsVersions;
    @Autowired
    TenantService tenantService;
    @Autowired
    TenantTransactions tenantTransactions;
    @Autowired
    JdbcClient jdbc;

    private UUID newTenant() {
        return tenantService.create("t-" + UUID.randomUUID(), "en").id();
    }

    private UUID newDocument(UUID tenant) {
        return tenantTransactions.run(tenant, () -> jdbc.sql("""
                        insert into documents (tenant_id, file_name, content_type, size_bytes, checksum, s3_key, status)
                        values (:tenant, 'a.md', 'text/markdown', 10, decode(md5(random()::text), 'hex'), 'k', 'ready')
                        returning id""")
                .param("tenant", tenant).query(UUID.class).single());
    }

    private int nextOrdinal;

    private void addChunks(UUID tenant, UUID document, int count) {
        for (int n = 0; n < count; n++) {
            int i = nextOrdinal++;
            jdbc.sql("""
                            insert into chunks (tenant_id, document_id, ordinal, content, token_count, ts_config)
                            values (:tenant, :document, :ordinal, 'text', 1, 'simple')""")
                    .param("tenant", tenant).param("document", document).param("ordinal", i).update();
        }
    }

    @Test
    void ingestingChunks_changesTheVersion_onceForTheWholeTransaction() {
        UUID tenant = newTenant();
        UUID doc = newDocument(tenant);
        long before = docsVersions.of(tenant);

        tenantTransactions.run(tenant, () -> addChunks(tenant, doc, 5));
        long after = docsVersions.of(tenant);
        tenantTransactions.run(tenant, () -> addChunks(tenant, doc, 1));

        assertThat(after).isNotEqualTo(before);
        assertThat(docsVersions.of(tenant)).isNotEqualTo(after);
    }

    // a new document's upload row alone changes nothing: answers change when its chunks arrive
    @Test
    void aDocumentRowWithoutChunks_leavesTheVersion() {
        UUID tenant = newTenant();
        long before = docsVersions.of(tenant);

        newDocument(tenant);

        assertThat(docsVersions.of(tenant)).isEqualTo(before);
    }

    // deleting a document deletes its chunks (on delete cascade), which changes the version
    @Test
    void deletingADocument_changesTheVersion() {
        UUID tenant = newTenant();
        UUID doc = newDocument(tenant);
        tenantTransactions.run(tenant, () -> addChunks(tenant, doc, 3));
        long before = docsVersions.of(tenant);

        tenantTransactions.run(tenant, () -> jdbc.sql("delete from documents where id = :id").param("id", doc).update());

        assertThat(docsVersions.of(tenant)).isNotEqualTo(before);
    }

    @Test
    void anotherTenantsChunks_leaveThisVersion() {
        UUID tenant = newTenant();
        UUID other = newTenant();
        UUID otherDoc = newDocument(other);
        long before = docsVersions.of(tenant);

        tenantTransactions.run(other, () -> addChunks(other, otherDoc, 2));

        assertThat(docsVersions.of(tenant)).isEqualTo(before);
    }

    // an ingestion that fails part way rolls back its chunks, and the version with them
    @Test
    void aRolledBackIngestion_leavesTheVersion() {
        UUID tenant = newTenant();
        UUID doc = newDocument(tenant);
        long before = docsVersions.of(tenant);

        Runnable failingIngestion = () -> {
            addChunks(tenant, doc, 2);
            throw new IllegalStateException("embedding failed");
        };
        assertThatThrownBy(() -> tenantTransactions.run(tenant, failingIngestion)).isInstanceOf(IllegalStateException.class);

        assertThat(docsVersions.of(tenant)).isEqualTo(before);
    }

    // the cascade deletes the chunks while the tenant row is going away; the trigger's update just finds no row
    @Test
    void deletingATenantWithChunks_works() {
        UUID tenant = newTenant();
        UUID doc = newDocument(tenant);
        tenantTransactions.run(tenant, () -> addChunks(tenant, doc, 2));

        jdbc.sql("delete from tenants where id = :id").param("id", tenant).update();

        assertThat(jdbc.sql("select count(*) from tenants where id = :id").param("id", tenant).query(Long.class).single())
                .isZero();
    }
}
