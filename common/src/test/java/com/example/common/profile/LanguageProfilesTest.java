package com.example.common.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Language names stay in the YAML files, so this test only compares values, never spells them out
class LanguageProfilesTest {

    private final LanguageProfiles profiles = new LanguageProfiles();

    LanguageProfilesTest() throws Exception {
    }

    @Test
    void bothProfilesLoad_withEveryFieldSet() {
        assertThat(profiles.ids()).contains("en", "tr");

        for (String id : profiles.ids()) {
            LanguageProfile p = profiles.get(id);
            assertThat(p.id()).isEqualTo(id);
            assertThat(p.embeddingModel()).isNotBlank();
            assertThat(p.textSearchConfig()).isNotBlank();
            assertThat(p.chunk().size()).isPositive();
            assertThat(p.chunk().overlap()).isNotNegative().isLessThan(p.chunk().size());
            assertThat(p.prompts().system()).isNotBlank();
            assertThat(p.prompts().noAnswer()).isNotBlank();
        }
    }

    @Test
    void profilesDifferWhereTheLanguageMatters() {
        LanguageProfile en = profiles.get("en");
        LanguageProfile tr = profiles.get("tr");

        assertThat(en.textSearchConfig()).isNotEqualTo(tr.textSearchConfig());
        assertThat(en.prompts().system()).isNotEqualTo(tr.prompts().system());
    }

    @Test
    void unknownProfile_isRejected() {
        assertThat(profiles.exists("de")).isFalse();
        assertThat(profiles.exists(null)).isFalse();
        assertThatThrownBy(() -> profiles.get("de")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overlapNotBelowSize_failsAtLoad() {
        assertThatThrownBy(() -> new LanguageProfiles("classpath*:broken-profiles/overlap/*.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("xx.yaml");
    }

    @Test
    void misspelledKey_failsAtLoad() {
        assertThatThrownBy(() -> new LanguageProfiles("classpath*:broken-profiles/typo/*.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("text-search");
    }

    @Test
    void noFiles_failsAtLoad() {
        assertThatThrownBy(() -> new LanguageProfiles("classpath*:no-such-dir/*.yaml"))
                .isInstanceOf(IllegalStateException.class);
    }
}
