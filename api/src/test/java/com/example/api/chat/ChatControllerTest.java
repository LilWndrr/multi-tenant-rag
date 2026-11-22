package com.example.api.chat;

import com.example.api.TestcontainersConfiguration;
import com.example.api.answer.Answer;
import com.example.api.apiKey.ApiKeyService;
import com.example.api.retrieve.RetrievedChunk;
import com.example.api.tenant.Tenant;
import com.example.api.tenant.TenantService;
import com.example.common.profile.LanguageProfile;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The pipeline is replaced (ChatServiceTest and the retriever, reranker and generator tests cover it);
// this checks the HTTP side: auth, validation, and the events a client reads.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChatControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TenantService tenantService;
    @Autowired
    ApiKeyService apiKeyService;

    @MockitoBean
    ChatService chatService;

    private record Event(String name, String data) {}

    private String keyFor(Tenant tenant) {
        return apiKeyService.create(tenant.id(), "website").key();
    }

    private static String body(String question) {
        return "{\"question\": \"" + question + "\"}";
    }

    // the service writes these tokens, then returns the answer
    private void answers(Answer answer, String... tokens) {
        when(chatService.answer(any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(3);
            for (String token : tokens) {
                onToken.accept(token);
            }
            return new ChatReply(answer.text(), ChatEvents.Citations.of(answer), false);
        });
    }

    private List<Event> chat(String key, String question) throws Exception {
        MvcResult result = mvc.perform(post("/v1/chat").header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(question)))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        return parse(result.getResponse().getContentAsString());
    }

    // "event:name\ndata:{...}\n\n" blocks, as an EventSource reads them
    private static List<Event> parse(String stream) {
        List<Event> events = new ArrayList<>();
        for (String block : stream.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                }
            }
            if (name != null) {
                events.add(new Event(name, data.toString()));
            }
        }
        return events;
    }

    @Test
    void tokensArriveInOrder_thenOneCitationsEvent_last() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        UUID doc = UUID.randomUUID();
        RetrievedChunk chunk = new RetrievedChunk(42, doc, "jobs.md", "Workloads > Jobs",
                "A Job retries a failed Pod.\n\nThe backoffLimit field defaults to 6.", 0.9f);
        answers(new Answer("Jobs retry six times [1].", true, List.of(new Answer.Citation(1, chunk))),
                "Jobs", " retry", " six times", " [1].");

        List<Event> events = chat(keyFor(tenant), "How often does a Job retry?");

        assertThat(events).extracting(Event::name).containsExactly("token", "token", "token", "token", "citations");
        // the leading spaces survive: each token is JSON, not bare text after "data:"
        String text = String.join("", events.stream().limit(4)
                .map(e -> JsonPath.<String>read(e.data(), "$.text")).toList());
        assertThat(text).isEqualTo("Jobs retry six times [1].");

        String citations = events.getLast().data();
        assertThat(JsonPath.<Boolean>read(citations, "$.answered")).isTrue();
        assertThat(JsonPath.<Integer>read(citations, "$.citations[0].number")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(citations, "$.citations[0].chunkId")).isEqualTo(42);
        assertThat(JsonPath.<String>read(citations, "$.citations[0].documentId")).isEqualTo(doc.toString());
        assertThat(JsonPath.<String>read(citations, "$.citations[0].document")).isEqualTo("jobs.md");
        assertThat(JsonPath.<String>read(citations, "$.citations[0].heading")).isEqualTo("Workloads > Jobs");
        assertThat(JsonPath.<String>read(citations, "$.citations[0].snippet"))
                .isEqualTo("A Job retries a failed Pod. The backoffLimit field defaults to 6.");
    }

    // the tenant and profile come from the API key, read on the request thread
    @Test
    void answersForTheKeysTenant_withItsProfile() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "tr");
        answers(new Answer("Bulamadım.", false, List.of()), "Bulamadım.");

        chat(keyFor(tenant), "  Soru?  ");

        verify(chatService).answer(eq(tenant.id()), any(LanguageProfile.class), eq("Soru?"), any());
        verify(chatService).answer(any(), org.mockito.ArgumentMatchers.argThat(p -> p.prompts().noAnswer()
                .startsWith("Belgelerinizde")), any(), any());
    }

    @Test
    void aRefusal_endsWithAnsweredFalse_andNoCitations() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        String no = "I could not find an answer to that in your documents.";
        answers(new Answer(no, false, List.of()), no);

        List<Event> events = chat(keyFor(tenant), "Best pizza in Rome?");

        assertThat(events).extracting(Event::name).containsExactly("token", "citations");
        assertThat(JsonPath.<Boolean>read(events.getLast().data(), "$.answered")).isFalse();
        assertThat(JsonPath.<List<?>>read(events.getLast().data(), "$.citations")).isEmpty();
    }

    // headers are already sent when the model fails, so the stream ends with an error event instead of a status
    @Test
    void aFailureAfterSomeTokens_endsWithAnErrorEvent() throws Exception {
        Tenant tenant = tenantService.create("t-" + UUID.randomUUID(), "en");
        when(chatService.answer(any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(3);
            onToken.accept("Jobs");
            throw new IllegalStateException("provider went away");
        });

        List<Event> events = chat(keyFor(tenant), "How often does a Job retry?");

        assertThat(events).extracting(Event::name).containsExactly("token", "error");
        assertThat(JsonPath.<String>read(events.getLast().data(), "$.message")).isNotBlank()
                .doesNotContain("provider went away");
    }

    @Test
    void withoutAKey_401() throws Exception {
        mvc.perform(post("/v1/chat").contentType(MediaType.APPLICATION_JSON).content(body("q")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(chatService);
    }

    @Test
    void aBlankOrTooLongQuestion_400_beforeAnythingRuns() throws Exception {
        String key = keyFor(tenantService.create("t-" + UUID.randomUUID(), "en"));
        for (String question : List.of("", "   ", "x".repeat(ChatController.MAX_QUESTION_LENGTH + 1))) {
            mvc.perform(post("/v1/chat").header("Authorization", "Bearer " + key)
                            .contentType(MediaType.APPLICATION_JSON).content(body(question)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/v1/chat").header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(chatService);
    }
}
