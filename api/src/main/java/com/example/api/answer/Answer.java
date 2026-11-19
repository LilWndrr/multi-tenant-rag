package com.example.api.answer;

import com.example.api.retrieve.RetrievedChunk;

import java.util.List;

// answered is false for the no-answer reply (no sources, or the model said the sources don't contain it);
// then citations is empty
public record Answer(String text, boolean answered, List<Citation> citations) {

    // number is the [n] in the text, which is the source's position in the prompt
    public record Citation(int number, RetrievedChunk chunk) {}
}
