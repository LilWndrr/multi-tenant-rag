package com.example.api;



import com.example.api.apiKey.ApiKeyService;
import com.example.api.document.IngestionQueue;
import com.example.api.tenant.Tenant;
import com.example.api.tenant.TenantService;
import com.example.common.ingestion.IngestionMessage;
import com.example.common.tenant.TenantTransactions;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.RequestBuilder;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;


import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;



import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public class DocumentTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TenantService tenantService;

    @Autowired
    ApiKeyService apiKeyService;

    @Autowired
    SqsClient sqsClient;

    @Autowired
    S3Client s3Client;
    @Autowired
    MultipartConfigElement multipartConfig;

    @Autowired
    TenantTransactions tenantTransactions;
    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    IngestionQueue ingestionQueue;

    @Test
    void  withLocation202_S3object_MessageInQueue_AndRow() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        byte[] content = "# Title\n\ntext".getBytes(StandardCharsets.UTF_8);
        String key =  apiKeyService.create(tenant.id(),"website").key();
        MockMultipartFile file = getFile(content,"notes.md");
       String body = mvc.perform(multipart("/v1/documents").file(file)
                        .header("Authorization", "Bearer " + key))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", startsWith("/v1/documents/")))
                .andExpect(jsonPath("$.status").value("queued"))
               .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));


        s3Client.headObject(r -> r.bucket("rag-documents").key(tenant.id() + "/" + id));


        String queueUrl = sqsClient.getQueueUrl(r -> r.queueName("ingestion")).queueUrl();
        // nothing consumes the queue in API tests, so other tests' messages are there too: read until ours shows up
        // (received messages stay hidden for the visibility timeout, so each read gets new ones)
        boolean found = false;
        for (int read = 0; read < 50 && !found; read++) {
            var messages = sqsClient.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1)).messages();
            found = messages.stream().anyMatch(m -> m.body().contains(id.toString()));
        }
        assertThat(found).as("ingestion message for " + id).isTrue();

        String status = tenantTransactions.run(tenant.id(), () ->
                jdbc.sql("select status from documents where id = :id").param("id", id).query(String.class).single());
        assertThat(status).isEqualTo("queued");
    }

    @Test
    void sameFile_gets200_withSameId() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        byte[] content = "# Title\n\ntext".getBytes(StandardCharsets.UTF_8);
        String key =  apiKeyService.create(tenant.id(),"website").key();
        MockMultipartFile file = getFile(content,"notes.md");
    String firstResponse =    mvc.perform(multipart("/v1/documents").file(file)
                        .header("Authorization", "Bearer " + key))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
    UUID firstId = UUID.fromString(JsonPath.read(firstResponse, "$.id"));
    String secondResponse=    mvc.perform(multipart("/v1/documents").file(file)
                        .header("Authorization", "Bearer " + key))
                .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    UUID secondId = UUID.fromString(JsonPath.read(secondResponse, "$.id"));
    assertThat(firstId).isEqualTo(secondId);
    }

    @Test
    void sameFile_getsNewId_withDifferentTenant() throws Exception {
        Tenant tenant1 = tenantService.create("t-" + UUID.randomUUID(), "en");
        byte[] content = "# Title\n\ntext".getBytes(StandardCharsets.UTF_8);
        String key1 =  apiKeyService.create(tenant1.id(),"website").key();
        MockMultipartFile file = getFile(content,"notes.md");
        String firstResponse =    mvc.perform(multipart("/v1/documents").file(file)
                        .header("Authorization", "Bearer " + key1))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();


        Tenant tenant2 = tenantService.create("t-" + UUID.randomUUID(), "en");
        String key2 =  apiKeyService.create(tenant2.id(),"website").key();
        UUID firstId = UUID.fromString(JsonPath.read(firstResponse, "$.id"));
        String secondResponse=    mvc.perform(multipart("/v1/documents").file(file)
                        .header("Authorization", "Bearer " + key2))
                .andExpect(status().isAccepted())   // new for tenant 2, not a duplicate
                .andReturn().getResponse().getContentAsString();
        UUID secondId = UUID.fromString(JsonPath.read(secondResponse, "$.id"));
        assertThat(firstId).isNotEqualTo(secondId);
    }
    @Test
    void emptyFile_gets400() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        byte[] content = new byte[0];
        String key =  apiKeyService.create(tenant.id(),"website").key();
        MockMultipartFile file = getFile(content,"notes.md");
          mvc.perform(multipart("/v1/documents").file(file)
                        .header("Authorization", "Bearer " + key))
                  .andExpect(status().isBadRequest());
    }
    @Test
    void exeFile_renamedToPdf_gets415() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        byte[] content = new byte[]{'M','Z',0,0};
        String key =  apiKeyService.create(tenant.id(),"website").key();
        MockMultipartFile file = getFile(content,"evil.pdf");
        mvc.perform(multipart("/v1/documents").file(file)
                        .header("Authorization", "Bearer " + key))
                .andExpect(status().isUnsupportedMediaType());
    }



    @Test
    void sqsDown_gets503_andTheDocumentIsMarkedFailed() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        String key = apiKeyService.create(tenant.id(), "website").key();
        doThrow(SdkClientException.create("SQS unreachable")).when(ingestionQueue).publish(any());

        mvc.perform(multipart("/v1/documents").file(getFile("# Title\n\ntext".getBytes(StandardCharsets.UTF_8), "notes.md"))
                        .header("Authorization", "Bearer " + key))
                .andExpect(status().isServiceUnavailable());

        // the 503 has no id in it, so find the row through the tenant: it's the only one this new tenant has
        Map<String, Object> row = tenantTransactions.run(tenant.id(), () ->
                jdbc.sql("select status, error from documents").query().singleRow());
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("error")).isEqualTo("could not queue for ingestion");
    }

    @Test
    void failedFile_uploadedAgain_isQueuedAgain() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        String key = apiKeyService.create(tenant.id(), "website").key();
        MockMultipartFile file = getFile("# Title\n\ntext".getBytes(StandardCharsets.UTF_8), "notes.md");
        doThrow(SdkClientException.create("SQS unreachable")).when(ingestionQueue).publish(any());
        mvc.perform(multipart("/v1/documents").file(file).header("Authorization", "Bearer " + key))
                .andExpect(status().isServiceUnavailable());
        UUID failedId = tenantTransactions.run(tenant.id(), () ->
                jdbc.sql("select id from documents").query(UUID.class).single());
        reset(ingestionQueue);   // SQS is back

        String body = mvc.perform(multipart("/v1/documents").file(file).header("Authorization", "Bearer " + key))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/v1/documents/" + failedId))
                .andExpect(jsonPath("$.status").value("queued"))
                .andReturn().getResponse().getContentAsString();

        assertThat(UUID.fromString(JsonPath.read(body, "$.id"))).isEqualTo(failedId);
        Map<String, Object> row = tenantTransactions.run(tenant.id(), () ->
                jdbc.sql("select status, error from documents").query().singleRow());
        assertThat(row.get("status")).isEqualTo("queued");
        assertThat(row.get("error")).isNull();
        verify(ingestionQueue, times(1)).publish(new IngestionMessage(failedId, tenant.id()));
        verifyNoMoreInteractions(ingestionQueue);
    }

    // Task 17's "done when": without a message the worker never runs, so nothing is embedded.
    @Test
    void readyFile_uploadedAgain_sendsNoMessage() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        String key = apiKeyService.create(tenant.id(), "website").key();
        MockMultipartFile file = getFile("# Title\n\ntext".getBytes(StandardCharsets.UTF_8), "notes.md");
        String first = mvc.perform(multipart("/v1/documents").file(file).header("Authorization", "Bearer " + key))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(first, "$.id"));
        tenantTransactions.run(tenant.id(), () ->
                jdbc.sql("update documents set status = 'ready' where id = :id").param("id", id).update());

        String second = mvc.perform(multipart("/v1/documents").file(file).header("Authorization", "Bearer " + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andReturn().getResponse().getContentAsString();

        assertThat(UUID.fromString(JsonPath.read(second, "$.id"))).isEqualTo(id);
        verify(ingestionQueue, times(1)).publish(any());   // only the first upload's
    }

    @Test
    void put_withNewContent_gets202_keepsTheId_andSwapsTheFile() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        String key = apiKeyService.create(tenant.id(), "website").key();
        UUID id = upload(key, "# Title\n\nfirst version", "notes.md");
        String oldS3Key = s3KeyOf(tenant.id(), id);
        clearInvocations(ingestionQueue);

        String body = mvc.perform(put(id, key, getFile("# Title\n\nsecond version".getBytes(StandardCharsets.UTF_8), "notes.md")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/v1/documents/" + id))
                .andExpect(jsonPath("$.status").value("queued"))
                .andReturn().getResponse().getContentAsString();

        assertThat(UUID.fromString(JsonPath.read(body, "$.id"))).isEqualTo(id);
        String newS3Key = s3KeyOf(tenant.id(), id);
        assertThat(newS3Key).isNotEqualTo(oldS3Key).startsWith(tenant.id() + "/" + id + "/");
        String stored = s3Client.getObjectAsBytes(r -> r.bucket("rag-documents").key(newS3Key)).asUtf8String();
        assertThat(stored).contains("second version");
        assertThatThrownBy(() -> s3Client.headObject(r -> r.bucket("rag-documents").key(oldS3Key)))
                .isInstanceOf(NoSuchKeyException.class);
        verify(ingestionQueue, times(1)).publish(new IngestionMessage(id, tenant.id()));
    }

    @Test
    void put_withTheSameContent_gets200_andSendsNoMessage() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        String key = apiKeyService.create(tenant.id(), "website").key();
        UUID id = upload(key, "# Title\n\ntext", "notes.md");
        String s3Key = s3KeyOf(tenant.id(), id);
        clearInvocations(ingestionQueue);

        mvc.perform(put(id, key, getFile("# Title\n\ntext".getBytes(StandardCharsets.UTF_8), "notes.md")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        assertThat(s3KeyOf(tenant.id(), id)).isEqualTo(s3Key);
        verify(ingestionQueue, never()).publish(any());
    }

    @Test
    void put_withTheContentOfAnotherDocument_gets409_andChangesNothing() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        String key = apiKeyService.create(tenant.id(), "website").key();
        UUID first = upload(key, "# One\n\nfirst", "one.md");
        upload(key, "# Two\n\nsecond", "two.md");
        String s3Key = s3KeyOf(tenant.id(), first);
        clearInvocations(ingestionQueue);

        mvc.perform(put(first, key, getFile("# Two\n\nsecond".getBytes(StandardCharsets.UTF_8), "two.md")))
                .andExpect(status().isConflict());

        assertThat(s3KeyOf(tenant.id(), first)).isEqualTo(s3Key);
        s3Client.headObject(r -> r.bucket("rag-documents").key(s3Key));   // the old file is still there
        assertThat(s3Client.listObjectsV2(r -> r.bucket("rag-documents").prefix(tenant.id() + "/" + first + "/")).contents())
                .isEmpty();   // and the new one was cleaned up
        verify(ingestionQueue, never()).publish(any());
    }

    @Test
    void put_onAnUnknownDocument_orAnotherTenantsDocument_gets404() throws Exception {
        Tenant owner = tenantService.create("t-" + UUID.randomUUID(), "en");
        UUID id = upload(apiKeyService.create(owner.id(), "website").key(), "# Title\n\ntext", "notes.md");
        Tenant other = tenantService.create("t-" + UUID.randomUUID(), "en");
        String otherKey = apiKeyService.create(other.id(), "website").key();
        MockMultipartFile file = getFile("# Title\n\nchanged".getBytes(StandardCharsets.UTF_8), "notes.md");

        mvc.perform(put(UUID.randomUUID(), otherKey, file)).andExpect(status().isNotFound());
        mvc.perform(put(id, otherKey, file)).andExpect(status().isNotFound());
    }

    @Test
    void delete_gets204_andRemovesTheRowChunksVectorsJobsAndFile() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        String key = apiKeyService.create(tenant.id(), "website").key();
        UUID id = upload(key, "# Title\n\ntext", "notes.md");
        String s3Key = s3KeyOf(tenant.id(), id);
        addIngestedData(tenant.id(), id);   // what the worker would have saved

        mvc.perform(delete("/v1/documents/{id}", id).header("Authorization", "Bearer " + key))
                .andExpect(status().isNoContent());

        tenantTransactions.run(tenant.id(), () -> {
            for (String table : List.of("documents", "chunks", "chunk_embeddings", "ingestion_jobs")) {
                assertThat(jdbc.sql("select count(*) from " + table).query(Integer.class).single())
                        .as(table).isZero();
            }
            return null;
        });
        assertThatThrownBy(() -> s3Client.headObject(r -> r.bucket("rag-documents").key(s3Key)))
                .isInstanceOf(NoSuchKeyException.class);
    }

    @Test
    void delete_onAnUnknownDocument_orAnotherTenantsDocument_gets404_andDeletesNothing() throws Exception {
        Tenant owner = tenantService.create("t-" + UUID.randomUUID(), "en");
        UUID id = upload(apiKeyService.create(owner.id(), "website").key(), "# Title\n\ntext", "notes.md");
        String s3Key = s3KeyOf(owner.id(), id);
        Tenant other = tenantService.create("t-" + UUID.randomUUID(), "en");
        String otherKey = apiKeyService.create(other.id(), "website").key();

        mvc.perform(delete("/v1/documents/{id}", UUID.randomUUID()).header("Authorization", "Bearer " + otherKey))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/v1/documents/{id}", id).header("Authorization", "Bearer " + otherKey))
                .andExpect(status().isNotFound());

        assertThat(s3KeyOf(owner.id(), id)).isEqualTo(s3Key);
        s3Client.headObject(r -> r.bucket("rag-documents").key(s3Key));
    }

    private void addIngestedData(UUID tenantId, UUID documentId) {
        tenantTransactions.run(tenantId, () -> {
            long chunkId = jdbc.sql("""
                            insert into chunks (tenant_id, document_id, ordinal, content, token_count, ts_config)
                            values (:tenantId, :documentId, 0, 'text', 1, 'simple') returning id
                            """)
                    .param("tenantId", tenantId).param("documentId", documentId)
                    .query(Long.class).single();
            jdbc.sql("insert into chunk_embeddings (chunk_id, tenant_id, model, embedding) values (:chunkId, :tenantId, 'm', '[1,2,3]')")
                    .param("chunkId", chunkId).param("tenantId", tenantId).update();
            jdbc.sql("insert into ingestion_jobs (tenant_id, document_id, status) values (:tenantId, :documentId, 'succeeded')")
                    .param("tenantId", tenantId).param("documentId", documentId).update();
            return null;
        });
    }

    private UUID upload(String apiKey, String content, String fileName) throws Exception {
        String body = mvc.perform(multipart("/v1/documents")
                        .file(getFile(content.getBytes(StandardCharsets.UTF_8), fileName))
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static RequestBuilder put(UUID id, String apiKey, MockMultipartFile file) {
        return multipart(HttpMethod.PUT, "/v1/documents/{id}", id).file(file).header("Authorization", "Bearer " + apiKey);
    }

    private String s3KeyOf(UUID tenantId, UUID documentId) {
        return tenantTransactions.run(tenantId, () -> jdbc.sql("select s3_key from documents where id = :id")
                .param("id", documentId).query(String.class).single());
    }

    @Test
    void uploadLimitIs20Mb() {
        assertThat(multipartConfig.getMaxFileSize()).isEqualTo(20L * 1024 * 1024);
    }

    private MockMultipartFile getFile (byte[] content,String fileName){
        return new MockMultipartFile(
                "file",
                fileName,
                "text/markdown",
                content);
    }

}
