package com.example.api.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

// Finished answers in Redis, per tenant (docs/decisions.md, Task 27). The key holds the tenant, its docs version (a
// change to its chunks gives a new one), a fingerprint of the settings that shape an answer, and a hash of the
// normalised question: never the question itself, which must not be stored (Task 26). Old entries aren't deleted;
// nothing asks for their keys again, and they expire.
// A cache failure is never a chat failure: reads and writes that fail are logged and skipped.
@Component
public class AnswerCache {

    private static final Logger log = LoggerFactory.getLogger(AnswerCache.class);

    // part of every key: changing what an entry holds makes the old entries unreachable
    static final String FORMAT = "a1";
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern TRAILING = Pattern.compile("[\\s?!.]+$");

    public record Entry(String text, ChatEvents.Citations citations) {
        // the text is document-derived: never in a log
        @Override
        public String toString() {
            return "Entry[" + text.length() + " chars, " + citations.citations().size() + " citations]";
        }
    }

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;

    public AnswerCache(StringRedisTemplate redis, JsonMapper json, @Value("${rag.cache.answer-ttl}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    public Optional<Entry> get(String key) {
        String value;
        try {
            value = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Answer cache read failed, answering without it: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, Entry.class));
        } catch (RuntimeException e) {
            log.warn("Answer cache entry unreadable, answering without it: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void put(String key, Entry entry) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(entry), ttl);
        } catch (RuntimeException e) {
            log.warn("Answer cache write failed: {}", e.getClass().getSimpleName());
        }
    }

    static String key(UUID tenantId, long docsVersion, String settings, String question, Locale locale) {
        return "answer:" + FORMAT + ":" + tenantId + ":" + docsVersion + ":" + sha256(settings).substring(0, 16)
                + ":" + sha256(normalise(question, locale));
    }

    // Questions that differ only in case, spacing or a final ?, ! or . share an entry. Lowercased with the profile's
    // locale: with tr, I lowercases to ı and İ to i. NFKC first, so the same letters typed differently (a
    // decomposed İ, full-width ？) compare equal.
    static String normalise(String question, Locale locale) {
        String text = Normalizer.normalize(question, Normalizer.Form.NFKC)
                .replace('？', '?').replace('！', '!').replace('。', '.');
        text = SPACES.matcher(text.strip()).replaceAll(" ").toLowerCase(locale);
        return TRAILING.matcher(text).replaceAll("");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
