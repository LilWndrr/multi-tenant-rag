package com.example.worker.document;

import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class DocumentParser {

    private final TikaDocumentParser tika;
    private final MarkdownDocumentParser markdown;

    public DocumentParser(TikaDocumentParser tika, MarkdownDocumentParser markdown) {
        this.tika = tika;
        this.markdown = markdown;
    }



    public ParsedDocument parse(InputStream content, String contentType) {
        return "text/markdown".equals(contentType)
                ? markdown.parse(content)
                : tika.parse(content, contentType);
    }
}
