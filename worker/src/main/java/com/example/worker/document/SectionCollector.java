package com.example.worker.document;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

class SectionCollector {

    private static final int MAX_CHARS = 20_000_000;

    private final List<ParsedSection> sections = new ArrayList<>();
    private final String[] headings = new String[6];
    private StringBuilder text = new StringBuilder();
    private int totalChars;

    void heading(int level, String title) {
        flush();
        String cleaned = title.strip();
        if (cleaned.isEmpty()) {
            return;
        }
        headings[level - 1] = cleaned;
        for (int i = level; i < headings.length; i++) {
            headings[i] = null;
        }
    }

    void append(CharSequence chars) {
        totalChars += chars.length();
        if (totalChars > MAX_CHARS) {
            throw new DocumentParseException("document has more than " + MAX_CHARS + " characters of text");
        }
        text.append(chars);
    }

    ParsedDocument finish() {
        flush();
        if (sections.isEmpty()) {
            throw new DocumentParseException("no text extracted");
        }
        return new ParsedDocument(List.copyOf(sections));
    }

    private void flush() {
        String content = text.toString().strip();
        if (!content.isEmpty()) {
            sections.add(new ParsedSection(path(), content));
        }
        text = new StringBuilder();
    }

    private String path() {
        StringJoiner path = new StringJoiner(" > ");
        for (String heading : headings) {
            if (heading != null) {
                path.add(heading);
            }
        }
        return path.toString();
    }
}
