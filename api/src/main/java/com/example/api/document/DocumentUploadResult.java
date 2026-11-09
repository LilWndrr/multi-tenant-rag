package com.example.api.document;

// queued = true: this request started processing, either a new file or a failed one sent again (202).
// queued = false: the tenant already had this file and it's queued, processing or ready; document is that one (200).
public record DocumentUploadResult(DocumentSaveResponse document, boolean queued) {
}
