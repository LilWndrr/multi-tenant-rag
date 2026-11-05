package com.example.api;



import com.example.api.apiKey.ApiKeyService;
import com.example.api.document.IngestionQueue;
import com.example.api.tenant.Tenant;
import com.example.api.tenant.TenantService;
import com.example.common.tenant.TenantTransactions;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;


import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;



import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
        var messages = sqsClient.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(2)).messages();
        assertThat(messages).anySatisfy(m -> assertThat(m.body()).contains(id.toString()));

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
