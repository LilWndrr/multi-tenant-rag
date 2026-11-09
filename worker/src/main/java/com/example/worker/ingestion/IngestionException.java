package com.example.worker.ingestion;

import com.example.common.enums.DocumentErrorCode;

// A pipeline failure tagged with the code clients see. The message stays the cause's, for the stored error and the logs.
public class IngestionException extends RuntimeException {

    private final DocumentErrorCode code;

    public IngestionException(DocumentErrorCode code, Throwable cause) {
        super(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName(), cause);
        this.code = code;
    }

    public DocumentErrorCode code() {
        return code;
    }
}
