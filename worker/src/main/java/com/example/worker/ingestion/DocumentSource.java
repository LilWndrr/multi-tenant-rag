package com.example.worker.ingestion;


public record DocumentSource(String s3Key, String contentType) {
}
