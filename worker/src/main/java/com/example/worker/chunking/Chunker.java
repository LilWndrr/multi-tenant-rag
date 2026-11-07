package com.example.worker.chunking;

import com.example.common.profile.LanguageProfile;
import com.example.worker.document.ParsedDocument;

import java.util.List;

public interface Chunker {
    List<TextChunk> chunk(ParsedDocument document, LanguageProfile.Chunk settings);
}
