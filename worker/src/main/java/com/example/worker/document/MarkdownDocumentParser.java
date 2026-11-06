package com.example.worker.document;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class MarkdownDocumentParser {

    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*?)[ \\t]*#*[ \\t]*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~).*");

    ParsedDocument parse(InputStream content) {
        String markdown;
        try {
            markdown = new String(content.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        SectionCollector collector = new SectionCollector();
        boolean inFence = false;
        for (String line : markdown.split("\\R", -1)) {
            if (FENCE.matcher(line).matches()) {
                inFence = !inFence;
            }
            Matcher heading = HEADING.matcher(line);
            if (!inFence && heading.matches()) {
                collector.heading(heading.group(1).length(), heading.group(2));
            } else {
                collector.append(line);
                collector.append("\n");
            }
        }
        return collector.finish();
    }
}
