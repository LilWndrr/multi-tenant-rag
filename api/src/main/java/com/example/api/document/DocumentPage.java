package com.example.api.document;

import java.util.List;

// nextCursor is null on the last page
public record DocumentPage(List<DocumentDetails> items, String nextCursor) {
}
