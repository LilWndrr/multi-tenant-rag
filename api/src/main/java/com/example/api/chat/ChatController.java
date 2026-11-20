package com.example.api.chat;

import com.example.api.answer.Answer;
import com.example.api.tenant.TenantContext;
import com.example.common.profile.LanguageProfile;
import com.example.common.profile.LanguageProfiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// POST /v1/chat {"question": "..."} answers as server-sent events: "token" events while the model writes, then one
// "citations" event, or an "error" event if the answer can't be finished (docs/decisions.md, Task 25).
// The question is never logged: only the tenant id, counts and outcomes.
@RestController
@RequestMapping("/v1")
public class ChatController implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    static final int MAX_QUESTION_LENGTH = 2000;
    static final long TIMEOUT_MS = 120_000;

    private final ChatService chatService;
    private final TenantContext tenantContext;
    private final LanguageProfiles profiles;
    // each answer waits on the network most of the time, so a virtual thread per answer
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ChatService chatService, TenantContext tenantContext, LanguageProfiles profiles) {
        this.chatService = chatService;
        this.tenantContext = tenantContext;
        this.profiles = profiles;
    }

    public record ChatRequest(String question) {}

    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(@RequestBody ChatRequest request) {
        String question = request.question() == null ? "" : request.question().strip();
        if (question.isEmpty() || question.length() > MAX_QUESTION_LENGTH) {
            return ResponseEntity.badRequest().build();
        }
        // TenantContext belongs to this request thread; the answer is written from another one
        UUID tenantId = tenantContext.getTenantId();
        LanguageProfile profile = profiles.get(tenantContext.getLanguageProfile());

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        executor.execute(() -> answer(emitter, tenantId, profile, question));
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private void answer(SseEmitter emitter, UUID tenantId, LanguageProfile profile, String question) {
        long start = System.nanoTime();
        try {
            Answer answer = chatService.answer(tenantId, profile, question,
                    token -> send(emitter, "token", new ChatEvents.Token(token)));
            send(emitter, "citations", ChatEvents.Citations.of(answer));
            emitter.complete();
            log.info("Chat for tenant {}: answered={}, {} citations, {} ms", tenantId, answer.answered(),
                    answer.citations().size(), (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            if (clientGone(e)) {
                // throwing from the token callback stops the model's stream, so no more tokens are paid for
                log.info("Chat for tenant {}: the client left after {} ms", tenantId, (System.nanoTime() - start) / 1_000_000);
                emitter.complete();
                return;
            }
            log.warn("Chat for tenant {} failed: {}", tenantId, e.getClass().getSimpleName());
            try {
                send(emitter, "error", new ChatEvents.Error("The answer could not be finished. Please try again."));
                emitter.complete();
            } catch (RuntimeException ignored) {
                emitter.complete();
            }
        }
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // IOException: the connection is closed; IllegalStateException: the emitter already timed out
            throw new ClientGoneException(e);
        }
    }

    private static boolean clientGone(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ClientGoneException) {
                return true;
            }
        }
        return false;
    }

    static final class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) {
            super(cause);
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
