package com.example.worker.chunking;


public record TextChunk(int ordinal, String headingPath, String content, int tokenCount) {}


