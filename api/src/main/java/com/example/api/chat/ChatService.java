package com.example.api.chat;

import com.example.api.answer.Answer;
import com.example.api.answer.Generator;
import com.example.api.retrieve.Reranker;
import com.example.api.retrieve.RetrievedChunk;
import com.example.api.retrieve.Retriever;
import com.example.common.profile.LanguageProfile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

// One question through the whole pipeline: hybrid retrieval (20 candidates), reranking down to the few the prompt
// gets, then the generator. Tokens go to onToken as they arrive (docs/decisions.md, Task 25).
// A question this tenant already had answered, with the same documents and settings, comes from the answer cache:
// no model calls (Task 27).
@Service
public class ChatService {

    static final int CANDIDATES = 20;

    private final Retriever retriever;
    private final Reranker reranker;
    private final Generator generator;
    private final AnswerCache cache;
    private final DocsVersions docsVersions;
    private final int sources;
    private final String chatModel;

    public ChatService(@Qualifier("hybrid-retriever") Retriever retriever, Reranker reranker, Generator generator,
                       AnswerCache cache, DocsVersions docsVersions,
                       @Value("${rag.chat.sources:6}") int sources, @Value("${rag.models.chat}") String chatModel) {
        this.retriever = retriever;
        this.reranker = reranker;
        this.generator = generator;
        this.cache = cache;
        this.docsVersions = docsVersions;
        this.sources = sources;
        this.chatModel = chatModel;
    }

    public ChatReply answer(UUID tenantId, LanguageProfile profile, String question, Consumer<String> onToken) {
        // read before answering: if the documents change while this answer is written, it is stored under the old
        // version, which no later question looks up
        String key = AnswerCache.key(tenantId, docsVersions.of(tenantId), settings(profile), question,
                profile.textLocale());
        Optional<AnswerCache.Entry> cached = cache.get(key);
        if (cached.isPresent()) {
            onToken.accept(cached.get().text());
            return new ChatReply(cached.get().text(), cached.get().citations(), true);
        }

        List<RetrievedChunk> candidates = retriever.retrieve(tenantId, profile, question, CANDIDATES);
        List<RetrievedChunk> best = reranker.rerank(tenantId, profile, question, candidates, sources);
        Answer answer = generator.generate(tenantId, profile, question, best, onToken);

        // reached only when the answer is complete: a failure or a client that left throws before this
        ChatEvents.Citations citations = ChatEvents.Citations.of(answer);
        cache.put(key, new AnswerCache.Entry(answer.text(), citations));
        return new ChatReply(answer.text(), citations, false);
    }

    // everything that shapes an answer besides the documents: the whole profile (models, thresholds, prompts) and
    // the pipeline's own settings
    private String settings(LanguageProfile profile) {
        return profile + "|" + chatModel + "|" + CANDIDATES + "|" + sources;
    }
}
