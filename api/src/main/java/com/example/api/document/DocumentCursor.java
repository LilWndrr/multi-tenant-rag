package com.example.api.document;

import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

// Where the next page starts: the last item's created_at and id. The id breaks ties, since rows inserted in one
// transaction share the same now(). Opaque to clients: URL-safe base64 of "<instant>|<uuid>".
// Instant keeps the microseconds Postgres stores, so the position survives the round trip exactly.
public record DocumentCursor(Instant createdAt, UUID id) {

    public String encode() {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static DocumentCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator < 0) {
                throw new IllegalArgumentException("no separator");
            }
            return new DocumentCursor(Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(BAD_REQUEST, "invalid cursor");
        }
    }
}
