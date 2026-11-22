package com.example.api.chat;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerCacheKeyTest {

    private static final Locale EN = Locale.forLanguageTag("en");
    private static final Locale TR = Locale.forLanguageTag("tr");
    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void caseSpacesAndAFinalMark_dontMatter() {
        String expected = AnswerCache.normalise("how do i reset my password", EN);

        assertThat(List.of("How do I reset my password?", "  how do i   reset\nmy password ", "HOW DO I RESET MY PASSWORD!!",
                "How do I reset my password？", "how do i reset my password."))
                .allSatisfy(q -> assertThat(AnswerCache.normalise(q, EN)).isEqualTo(expected));
    }

    // words, and punctuation inside the question, still count
    @Test
    void anythingElse_matters() {
        assertThat(AnswerCache.normalise("What is C++?", EN)).isEqualTo("what is c++");
        assertThat(AnswerCache.normalise("Is it ready?", EN)).isNotEqualTo(AnswerCache.normalise("Is it ready yet?", EN));
    }

    // With the tr locale, I lowercases to dotless ı and İ to i. A language-neutral lowercase gets both wrong, so the profile's
    // locale is used; a decomposed İ (I plus a combining dot) is the same letter after NFKC.
    @Test
    void lowercasing_followsTheProfilesLocale() {
        assertThat(AnswerCache.normalise("IRMAK", TR)).isEqualTo("ırmak");
        assertThat(AnswerCache.normalise("İSTANBUL'DA NE VAR?", TR)).isEqualTo("istanbul'da ne var");
        assertThat(AnswerCache.normalise("İstanbul", TR)).isEqualTo(AnswerCache.normalise("İstanbul", TR));
        assertThat(AnswerCache.normalise("IRMAK", EN)).isEqualTo("irmak");
    }

    // Task 26: the question is never stored, so the key holds a hash of it
    @Test
    void theKey_holdsAHash_notTheQuestion() {
        String key = AnswerCache.key(TENANT, 42, "settings", "How do I reset my password?", EN);

        assertThat(key).startsWith("answer:" + AnswerCache.FORMAT + ":" + TENANT + ":42:")
                .doesNotContainIgnoringCase("password");
        assertThat(key).isEqualTo(AnswerCache.key(TENANT, 42, "settings", "how do I reset my password", EN));
    }

    @Test
    void theKey_differsByTenantVersionAndSettings() {
        String key = AnswerCache.key(TENANT, 42, "settings", "q", EN);

        assertThat(AnswerCache.key(UUID.randomUUID(), 42, "settings", "q", EN)).isNotEqualTo(key);
        assertThat(AnswerCache.key(TENANT, 43, "settings", "q", EN)).isNotEqualTo(key);
        assertThat(AnswerCache.key(TENANT, 42, "other settings", "q", EN)).isNotEqualTo(key);
    }

    // nothing listens on port 1: reads miss and writes are skipped, quickly, so chat answers without the cache
    @Test
    void redisUnreachable_isAMiss_andAWriteIsSkipped() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
        factory.afterPropertiesSet();
        AnswerCache cache = new AnswerCache(new StringRedisTemplate(factory), JsonMapper.builder().build(), Duration.ofHours(1));
        try {
            long start = System.nanoTime();
            assertThat(cache.get("answer:x")).isEmpty();
            cache.put("answer:x", new AnswerCache.Entry("t", new ChatEvents.Citations(false, List.of())));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        } finally {
            factory.destroy();
        }
    }
}
