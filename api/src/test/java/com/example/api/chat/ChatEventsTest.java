package com.example.api.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEventsTest {

    @Test
    void aShortChunk_isTheWholeChunkOnOneLine() {
        assertThat(ChatEvents.snippetOf("  A Job\n\nretries   a Pod.  ", 200)).isEqualTo("A Job retries a Pod.");
    }

    @Test
    void aLongChunk_isCutAtAWordBoundary() {
        String snippet = ChatEvents.snippetOf("word ".repeat(100), 22);

        assertThat(snippet).isEqualTo("word word word word…");
    }

    // one long token (a URL, a hash) is cut where it is rather than leaving almost nothing
    @Test
    void noSpaceNearTheEnd_cutsAtTheLimit() {
        assertThat(ChatEvents.snippetOf("ab " + "x".repeat(50), 20)).isEqualTo("ab " + "x".repeat(17) + "…");
    }
}
