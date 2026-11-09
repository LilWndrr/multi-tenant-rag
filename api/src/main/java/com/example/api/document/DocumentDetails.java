package com.example.api.document;

import com.example.common.enums.DocumentErrorCode;
import com.example.common.enums.DocumentProcessStatus;

import java.time.Instant;
import java.util.UUID;

// What GET returns. error is the short code only, set when status is failed; the raw message never leaves the server.
// No s3_key or checksum: those are internal.
public record DocumentDetails(UUID id, String fileName, String contentType, long sizeBytes,
                              DocumentProcessStatus status, DocumentErrorCode error,
                              Instant createdAt, Instant updatedAt) {
}
