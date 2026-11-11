package com.example.api.retrieve;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Inputs are real websearch_to_tsquery output, taken from Postgres.
class TsQueriesTest {

    @Test
    void everyWordIsItsOwnPart() {
        TsQueries.Parts parts = TsQueries.split("'pod' & 'stuck' & 'crashloopbackoff'");

        assertThat(parts.wanted()).containsExactly("'pod'", "'stuck'", "'crashloopbackoff'");
        assertThat(parts.excluded()).isEmpty();
    }

    @Test
    void exclusionsAreSetApart_withoutTheirBang() {
        TsQueries.Parts parts = TsQueries.split("'pod' & 'stuck' & !'restart' & !'evict'");

        assertThat(parts.wanted()).containsExactly("'pod'", "'stuck'");
        assertThat(parts.anyExcluded()).isEqualTo("'restart' | 'evict'");
    }

    // from: "image pull" kubelet or kubectl
    @Test
    void phrasesAndOrGroupsStayWhole() {
        assertThat(TsQueries.split("'imag' <-> 'pull' & 'kubelet' | 'kubectl'").wanted())
                .containsExactly("'imag' <-> 'pull'", "'kubelet' | 'kubectl'");
    }

    @Test
    void onlyStopWordsOrOnlyExclusions_meansNothingToSearch() {
        assertThat(TsQueries.split("").isEmpty()).isTrue();
        assertThat(TsQueries.split(null).isEmpty()).isTrue();
        assertThat(TsQueries.split("!'restart'").isEmpty()).isTrue();
    }
}
