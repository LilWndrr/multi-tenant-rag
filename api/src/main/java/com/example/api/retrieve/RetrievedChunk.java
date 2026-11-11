package com.example.api.retrieve;

import java.util.UUID;

public record RetrievedChunk (long id, UUID documentId,String fileName,String headingPath, String content, float score) {
}
