package com.example.api.document;

import com.example.common.enums.DocumentProcessStatus;

// What a replace needs to know about the current version of a document.
public record StoredDocument(byte[] checksum, DocumentProcessStatus status, String s3Key) {
}
