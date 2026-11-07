package com.example.worker.document;

import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.metadata.HttpHeaders;
import org.springframework.stereotype.Component;
import org.apache.tika.metadata.Metadata;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

@Component
class TikaDocumentParser {

    private final AutoDetectParser parser = new AutoDetectParser();

    ParsedDocument parse(InputStream content, String contentType) {
        SectionContentHandler handler = new SectionContentHandler();
        Metadata metadata = new Metadata();
        metadata.set(HttpHeaders.CONTENT_TYPE, contentType);
        try (TikaInputStream stream = TikaInputStream.get(content)) {
            parser.parse(stream, handler, metadata, new ParseContext());
        } catch (IOException | SAXException | TikaException e) {
            throw new DocumentParseException("could not parse " + contentType + ": " + e.getMessage(), e);
        }
        return handler.finish();
    }
}
