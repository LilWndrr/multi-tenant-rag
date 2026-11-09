package com.example.api;

import com.example.api.document.DocumentCursor;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentCursorTest {

    @Test
    void roundTrip_keepsMicroseconds() {
        DocumentCursor cursor = new DocumentCursor(Instant.parse("2026-11-09T10:15:30.123456Z"), UUID.randomUUID());

        assertThat(DocumentCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void encoded_isSafeInAQueryString() {
        String encoded = new DocumentCursor(Instant.now(), UUID.randomUUID()).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void anythingElse_is400() {
        String noSeparator = base64("2026-11-09T10:15:30Z");
        String badDate = base64("yesterday|" + UUID.randomUUID());
        String badId = base64("2026-11-09T10:15:30Z|nope");

        for (String cursor : new String[]{"!!!", noSeparator, badDate, badId}) {
            assertThatThrownBy(() -> DocumentCursor.decode(cursor))
                    .as(cursor)
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("invalid cursor");
        }
    }

    private static String base64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
