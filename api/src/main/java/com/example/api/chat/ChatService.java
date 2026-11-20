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
import java.util.UUID;
import java.util.function.Consumer;

// One question through the whole pipeline: hybrid retrieval (20 candidates), reranking down to the few the prompt
// gets, then the generator. Tokens go to onToken as they arrive (docs/decisions.md, Task 25).
@Service
public class ChatService {

    static final int CANDIDATES = 20;

    private final Retriever retriever;
    private final Reranker reranker;
    private final Generator generator;
    private final int sources;

    public ChatService(@Qualifier("hybrid-retriever") Retriever retriever, Reranker reranker, Generator generator,
                       @Value("${rag.chat.sources:6}") int sources) {
        this.retriever = retriever;
        this.reranker = reranker;
        this.generator = generator;
        this.sources = sources;
    }

    public Answer answer(UUID tenantId, LanguageProfile profile, String question, Consumer<String> onToken) {
        List<RetrievedChunk> candidates = retriever.retrieve(tenantId, profile, question, CANDIDATES);
        List<RetrievedChunk> best = reranker.rerank(tenantId, profile, question, candidates, sources);
        return generator.generate(tenantId, profile, question, best, onToken);
    }
}
