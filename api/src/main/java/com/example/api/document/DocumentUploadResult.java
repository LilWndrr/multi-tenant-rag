package com.example.api.document;

// created = false: the tenant already had a file with this checksum, and document is that one (200, not 202).
public record DocumentUploadResult(DocumentSaveResponse document, boolean created) {
}
