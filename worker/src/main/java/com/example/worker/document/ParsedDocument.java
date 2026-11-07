package com.example.worker.document;

import java.util.List;

public record ParsedDocument (List<ParsedSection> sections) {
}
