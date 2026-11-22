package com.example.api.chat;

import com.example.api.TestcontainersConfiguration;
import com.example.api.apiKey.ApiKeyService;
import com.example.api.tenant.TenantService;
import com.example.common.model.ModelChatResponse;
import com.example.common.model.ModelEmbedResponse;
import com.example.common.model.ModelGateway;
import com.example.common.model.ModelRerankResponse;
import com.example.common.model.Usage;
import com.example.common.profile.LanguageProfiles;
import com.example.common.tenant.TenantTransactions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The plan's check for Task 26: a question's text is in no log line and no table, with the request going through the
// real pipeline (retrievers, reranker, generator, Postgres) and Spring's web logging on DEBUG, as when debugging.
// Only the model provider is replaced. A made-up word marks the question and the document, so any trace of either shows.
@SpringBootTest(properties = {
        "logging.level.com.example=TRACE",
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.org.springframework.jdbc=DEBUG",
        "logging.level.org.springframework.ai=DEBUG"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class QuestionNotLoggedTest {

    private static final String MARKER = "Quokkafjord" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final String QUESTION = "What does the " + MARKER + " setting control?";

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
    @Autowired
    LanguageProfiles profiles;

    @MockitoBean
    ModelGateway modelGateway;

    @Test
    void aQuestionThroughTheWholePipeline_isInNoLogLine_andNoTable(CapturedOutput output) throws Exception {
        UUID tenant = tenantService.create("t-" + UUID.randomUUID(), "en").id();
        String key = apiKeyService.create(tenant, "website").key();
        addChunk(tenant, "The " + MARKER + " setting controls how often the scheduler runs.");
        providerAnswers("It controls how often the scheduler runs [1]. " + MARKER + " is a setting.");

        MvcResult result = mvc.perform(post("/v1/chat").header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"" + QUESTION + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);

        // the whole pipeline ran: full-text search found the chunk, so the reranker and the answer model were called
        assertThat(result.getResponse().getContentAsString()).contains("event:citations").contains("\"chunkId\"");
        verify(modelGateway).stream(any(), any(), anyList());
        assertThat(output.getAll()).contains("Chat for tenant " + tenant);

        assertThat(output.getAll()).doesNotContain(MARKER);
        assertThat(tablesContaining(tenant, QUESTION)).isEmpty();
    }

    // a broken body is refused, and the parser's error message doesn't carry its text into the log
    @Test
    void aMalformedBody_isRefused_withoutItsTextInTheLog(CapturedOutput output) throws Exception {
        String key = apiKeyService.create(tenantService.create("t-" + UUID.randomUUID(), "en").id(), "website").key();

        mvc.perform(post("/v1/chat").header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": " + MARKER + " was it?}"))
                .andExpect(status().isBadRequest());

        assertThat(output.getAll()).doesNotContain(MARKER);
    }

    private void providerAnswers(String answer) {
        float[] vector = new float[profiles.get("en").embeddingDimensions()];
        java.util.Arrays.fill(vector, 0.01f);
        when(modelGateway.embed(any(), anyList())).thenReturn(
                new ModelEmbedResponse(List.of(vector), "m/embed", new Usage(5, 0, 0, null)));
        when(modelGateway.rerank(any(), anyString(), anyList())).thenAnswer(inv -> {
            List<String> documents = inv.getArgument(2);
            List<ModelRerankResponse.Score> scores = new java.util.ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                scores.add(new ModelRerankResponse.Score(i, 0.9));
            }
            return new ModelRerankResponse(scores, "m/rerank", new Usage(100, 0, 0, null));
        });
        when(modelGateway.stream(any(), any(), anyList())).thenAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(1);
            onToken.accept(answer);
            return new ModelChatResponse(answer, "m/chat", new Usage(100, 10, 0, null));
        });
    }

    private void addChunk(UUID tenant, String content) {
        tenantTransactions.run(tenant, () -> {
            UUID doc = jdbc.sql("""
                            insert into documents (tenant_id, file_name, content_type, size_bytes, checksum, s3_key, status)
                            values (:tenant, 'settings.md', 'text/markdown', 10, decode(md5(random()::text), 'hex'), 'k', 'ready')
                            returning id
                            """)
                    .param("tenant", tenant).query(UUID.class).single();
            return jdbc.sql("""
                            insert into chunks (tenant_id, document_id, ordinal, content, token_count, ts_config)
                            values (:tenant, :doc, 0, :content, 1, cast(:config as regconfig))
                            """)
                    .param("tenant", tenant).param("doc", doc).param("content", content)
                    .param("config", profiles.get("en").textSearchConfig()).update();
        });
    }

    // every table's rows as text, as this tenant sees them under row-level security
    private List<String> tablesContaining(UUID tenant, String text) {
        List<String> tables = jdbc.sql("select table_name from information_schema.tables "
                        + "where table_schema = 'public' and table_type = 'BASE TABLE'")
                .query(String.class).list();
        return tenantTransactions.run(tenant, () -> tables.stream()
                .filter(table -> jdbc.sql("select count(*) from " + table + " t where t::text like :text")
                        .param("text", "%" + text + "%").query(Long.class).single() > 0)
                .toList());
    }
}
