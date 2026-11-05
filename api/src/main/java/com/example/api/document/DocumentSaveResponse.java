package com.example.api.document;

import java.util.UUID;

public record DocumentSaveResponse (UUID id,DocumentProcessStatus status) {
}
