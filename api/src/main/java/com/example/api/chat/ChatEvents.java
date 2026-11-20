package com.example.api.chat;

import com.example.api.answer.Answer;

import java.util.List;
import java.util.UUID;

// The data of each server-sent event. Every event's data is JSON: SSE drops one leading space after "data:", so a
// token sent as plain text (" retry") would lose the space between words.
public final class ChatEvents {

    static final int SNIPPET_LENGTH = 200;

    private ChatEvents() {
    }


    public record Token(String text) {
        @Override
        public String toString() {
            return "Token[" + text.length() + " chars]";
        }
    }


    public record Citations(boolean answered, List<Citation> citations) {

        static Citations of(Answer answer) {
            return new Citations(answer.answered(), answer.citations().stream().map(Citation::of).toList());
        }
    }

    // number is the [n] in the answer text
    public record Citation(int number, long chunkId, UUID documentId, String document, String heading, String snippet) {

        static Citation of(Answer.Citation c) {
            return new Citation(c.number(), c.chunk().id(), c.chunk().documentId(), c.chunk().fileName(),
                    c.chunk().headingPath(), snippetOf(c.chunk().content(), SNIPPET_LENGTH));
        }

        @Override
        public String toString() {
            return "Citation[number=" + number + ", chunkId=" + chunkId + "]";
        }
    }

    // event: error, when the answer can't be finished; the client keeps what it has shown and offers a retry
    public record Error(String message) {}

    // the start of the chunk on one line, cut at a word boundary
    static String snippetOf(String content, int max) {
        String text = content.strip().replaceAll("\\s+", " ");
        if (text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf(' ', max);
        return (cut > max / 2 ? text.substring(0, cut) : text.substring(0, max)) + "…";
    }
}
