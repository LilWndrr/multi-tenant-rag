package com.example.worker.document;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.util.Set;

class SectionContentHandler extends DefaultHandler {

    private static final Set<String> BLOCKS = Set.of("p", "div", "li", "tr", "br", "blockquote", "pre");

    private final SectionCollector collector = new SectionCollector();
    private StringBuilder heading;   // not null while inside <h1>..<h6>
    private int headDepth;           // inside <head>: skips the <title>

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        if (localName.equals("head")) {
            headDepth++;
        } else if (headingLevel(localName) > 0) {
            heading = new StringBuilder();
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        int level = headingLevel(localName);
        if (localName.equals("head")) {
            headDepth--;
        } else if (level > 0 && heading != null) {
            collector.heading(level, heading.toString());
            heading = null;
        } else if (BLOCKS.contains(localName) && heading == null) {
            collector.append("\n");   // keeps paragraphs apart
        }
    }

    @Override
    public void characters(char[] chars, int start, int length) {
        if (headDepth > 0) {
            return;
        }
        if (heading != null) {
            heading.append(chars, start, length);
        } else {
            collector.append(new String(chars, start, length));
        }
    }

    ParsedDocument finish() {
        return collector.finish();
    }

    private static int headingLevel(String name) {
        boolean isHeading = name.length() == 2 && name.charAt(0) == 'h' && name.charAt(1) >= '1' && name.charAt(1) <= '6';
        return isHeading ? name.charAt(1) - '0' : 0;
    }
}