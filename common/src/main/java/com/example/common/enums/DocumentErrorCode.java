package com.example.common.enums;

// Why a document failed, as clients see it. The worker and the API both write it; V5 checks the same list.
public enum DocumentErrorCode {
    queue_unavailable,   // the API could not send the ingestion message; uploading again retries
    file_unavailable,    // the worker could not download the file from S3
    parse_failed,        // the file could not be read as its type
    embedding_failed,    // the model provider failed or returned something unusable
    internal_error       // anything else
}
