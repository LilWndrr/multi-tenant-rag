package com.example.api.retrieve;

import com.example.common.profile.LanguageProfile;

import java.util.List;
import java.util.UUID;

public interface Retriever {

    List<RetrievedChunk> retrieve(UUID tenantId, LanguageProfile profile, String question, int limit);
}
