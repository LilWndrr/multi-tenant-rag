package com.example.api.document;

import com.example.common.enums.DocumentProcessStatus;

import java.util.UUID;

public record DocumentSaveResponse (UUID id, DocumentProcessStatus status) {
}
