package com.example.api;

import com.example.api.apiKey.ApiKeyService;
import com.example.api.document.IngestionQueue;
import com.example.api.tenant.Tenant;
import com.example.api.tenant.TenantService;
import com.example.common.tenant.TenantTransactions;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /v1/documents/{id} and GET /v1/documents. The worker isn't running here: the tests set the statuses
// the worker would set, and the worker's own tests show it really moves them.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocumentQueryTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TenantService tenantService;
    @Autowired
    ApiKeyService apiKeyService;
    @Autowired
    TenantTransactions tenantTransactions;
    @Autowired
    JdbcClient jdbc;
    @MockitoSpyBean
    IngestionQueue ingestionQueue;

    private record Client(UUID tenantId, String apiKey) {}

    @Test
    void get_showsTheStatusAsItMoves_queuedProcessingReady() throws Exception {
        Client client = newClient();
        UUID id = upload(client, "# Title\n\ntext", "notes.md");

        for (String status : List.of("queued", "processing", "ready")) {
            if (!status.equals("queued")) {
                setStatus(client, id, status, null, null);
            }
            mvc.perform(get("/v1/documents/{id}", id).header("Authorization", "Bearer " + client.apiKey()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.status").value(status))
                    .andExpect(jsonPath("$.error").isEmpty())
                    .andExpect(jsonPath("$.fileName").value("notes.md"))
                    .andExpect(jsonPath("$.contentType").value("text/markdown"))
                    .andExpect(jsonPath("$.sizeBytes").value(13))
                    .andExpect(jsonPath("$.createdAt").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z")))
                    .andExpect(jsonPath("$.s3Key").doesNotExist())
                    .andExpect(jsonPath("$.checksum").doesNotExist());
        }
    }

    @Test
    void failedDocument_showsOnlyTheShortCode_neverTheRawMessage() throws Exception {
        Client client = newClient();
        UUID id = upload(client, "# Title\n\ntext", "notes.md");
        setStatus(client, id, "failed", "embedding_failed", "provider said: key sk-or-test rejected for bucket rag-documents");

        String body = mvc.perform(get("/v1/documents/{id}", id).header("Authorization", "Bearer " + client.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error").value("embedding_failed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("provider said", "rag-documents");
    }

    @Test
    void uploadWhileSqsIsDown_showsQueueUnavailable() throws Exception {
        Client client = newClient();
        doThrow(SdkClientException.create("SQS unreachable")).when(ingestionQueue).publish(any());
        mvc.perform(multipart("/v1/documents").file(file("# Title\n\ntext", "notes.md"))
                        .header("Authorization", "Bearer " + client.apiKey()))
                .andExpect(status().isServiceUnavailable());
        UUID id = tenantTransactions.run(client.tenantId(), () ->
                jdbc.sql("select id from documents").query(UUID.class).single());

        mvc.perform(get("/v1/documents/{id}", id).header("Authorization", "Bearer " + client.apiKey()))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error").value("queue_unavailable"));
    }

    @Test
    void get_onAnUnknownDocument_orAnotherTenantsDocument_gets404() throws Exception {
        Client owner = newClient();
        UUID id = upload(owner, "# Title\n\ntext", "notes.md");
        Client other = newClient();

        mvc.perform(get("/v1/documents/{id}", UUID.randomUUID()).header("Authorization", "Bearer " + other.apiKey()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/documents/{id}", id).header("Authorization", "Bearer " + other.apiKey()))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_pagesNewestFirst_withoutGapsOrRepeats_andOnlyTheTenantsDocuments() throws Exception {
        Client client = newClient();
        List<UUID> uploaded = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            uploaded.add(upload(client, "# Doc " + i + "\n\ntext " + i, "doc" + i + ".md"));
        }
        // two documents with the same created_at: the id has to break the tie at a page edge
        tenantTransactions.run(client.tenantId(), () -> jdbc.sql("""
                        update documents set created_at = (select created_at from documents where id = :a) where id = :b
                        """)
                .param("a", uploaded.get(1)).param("b", uploaded.get(2)).update());
        upload(newClient(), "# Someone else\n\ntext", "other.md");

        List<UUID> expected = tenantTransactions.run(client.tenantId(), () ->
                jdbc.sql("select id from documents order by created_at desc, id desc").query(UUID.class).list());

        List<UUID> seen = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            var request = get("/v1/documents").param("limit", "2").header("Authorization", "Bearer " + client.apiKey());
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(body, "$.items[*].id");
            ids.forEach(id -> seen.add(UUID.fromString(id)));
            pageSizes.add(ids.size());
            cursor = JsonPath.read(body, "$.nextCursor");
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(expected).containsExactlyInAnyOrderElementsOf(uploaded);
        assertThat(pageSizes).containsExactly(2, 2, 1);
    }

    @Test
    void list_withExactlyOneFullPage_hasNoNextCursor() throws Exception {
        Client client = newClient();
        upload(client, "# One\n\ntext", "one.md");
        upload(client, "# Two\n\ntext", "two.md");

        mvc.perform(get("/v1/documents").param("limit", "2").header("Authorization", "Bearer " + client.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void list_withABadLimitOrCursor_gets400() throws Exception {
        Client client = newClient();

        for (String limit : List.of("0", "101", "abc")) {
            mvc.perform(get("/v1/documents").param("limit", limit).header("Authorization", "Bearer " + client.apiKey()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/v1/documents").param("cursor", "not-a-cursor").header("Authorization", "Bearer " + client.apiKey()))
                .andExpect(status().isBadRequest());
    }

    private Client newClient() {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        return new Client(tenant.id(), apiKeyService.create(tenant.id(), "website").key());
    }

    private UUID upload(Client client, String content, String fileName) throws Exception {
        String body = mvc.perform(multipart("/v1/documents").file(file(content, fileName))
                        .header("Authorization", "Bearer " + client.apiKey()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static MockMultipartFile file(String content, String fileName) {
        return new MockMultipartFile("file", fileName, "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }

    private void setStatus(Client client, UUID id, String status, String errorCode, String error) {
        tenantTransactions.run(client.tenantId(), () -> jdbc.sql("""
                        update documents set status = :status, error_code = :code, error = :error where id = :id
                        """)
                .param("status", status).param("code", errorCode).param("error", error).param("id", id)
                .update());
    }
}
