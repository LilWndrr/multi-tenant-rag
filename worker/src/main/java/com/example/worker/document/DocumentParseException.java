package com.example.worker.document;

public class DocumentParseException extends RuntimeException {
    public DocumentParseException(String message) { super(message); }
    public DocumentParseException(String message, Throwable cause) { super(message, cause); }
}
