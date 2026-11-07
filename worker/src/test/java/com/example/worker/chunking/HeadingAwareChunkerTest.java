package com.example.worker.chunking;

import com.example.common.profile.LanguageProfile;
import com.example.worker.document.ParsedDocument;
import com.example.worker.document.ParsedSection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class HeadingAwareChunkerTest {

    private static final int SIZE = 50;
    private static final LanguageProfile.Chunk SMALL = new LanguageProfile.Chunk(SIZE, 10);
    // nothing repeated, so joining the chunks gives back the original text
    private static final LanguageProfile.Chunk NO_OVERLAP = new LanguageProfile.Chunk(SIZE, 0);

    private final TokenCounter counter = new TokenCounter();
    private final HeadingAwareChunker chunker = new HeadingAwareChunker(counter);

    private List<TextChunk> chunk(LanguageProfile.Chunk settings, ParsedSection... sections) {
        return chunker.chunk(new ParsedDocument(List.of(sections)), settings);
    }

    // Capitalised on purpose: the sentence detector doesn't break before a lowercase word.
    private static String sentence(int number) {
        return "Cümle numarası " + number + " burada ığüşöçİĞ ile biter.";
    }

    private static String sentences(int count) {
        return IntStream.range(0, count)
                .mapToObj(HeadingAwareChunkerTest::sentence)
                .collect(Collectors.joining(" "));
    }

    private static String words(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "kelime" + i)
                .collect(Collectors.joining(" "));
    }

    private static String joined(List<TextChunk> chunks) {
        return chunks.stream().map(TextChunk::content).collect(Collectors.joining());
    }

    private void assertWithinLimit(List<TextChunk> chunks) {
        assertThat(chunks).isNotEmpty();
        for (TextChunk c : chunks) {
            assertThat(counter.countTokens(c.content())).isEqualTo(c.tokenCount()).isLessThanOrEqualTo(SIZE);
        }
    }

    @Test
    void noChunkIsOverTheLimit() {
        assertWithinLimit(chunk(SMALL, new ParsedSection("A", sentences(300))));
    }

    @Test
    void chunksKeepTheirHeading_andOrdinalsRunAcrossSections() {
        List<TextChunk> chunks = chunk(SMALL,
                new ParsedSection("A", sentences(40).replace("Cümle", "Alfa")),
                new ParsedSection("A > B", sentences(40).replace("Cümle", "Beta")));

        assertThat(chunks).extracting(TextChunk::ordinal)
                .isEqualTo(IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).extracting(TextChunk::headingPath).contains("A", "A > B");
        for (TextChunk c : chunks) {
            if (c.headingPath().equals("A")) {
                assertThat(c.content()).contains("Alfa").doesNotContain("Beta");
            } else {
                assertThat(c.headingPath()).isEqualTo("A > B");
                assertThat(c.content()).contains("Beta").doesNotContain("Alfa");
            }
        }
    }

    @Test
    void sentencesStayWhole() {
        String all = chunk(SMALL, new ParsedSection("A", sentences(100))).stream()
                .map(TextChunk::content)
                .collect(Collectors.joining("\n"));

        for (int i = 0; i < 100; i++) {
            assertThat(all).contains(sentence(i));
        }
    }

    @Test
    void shortSection_isOneUnchangedChunk() {
        List<TextChunk> chunks = chunk(SMALL, new ParsedSection("A", "Kısa metin."));

        assertThat(chunks).hasSize(1);
        TextChunk only = chunks.getFirst();
        assertThat(only.ordinal()).isZero();
        assertThat(only.headingPath()).isEqualTo("A");
        assertThat(only.content()).isEqualTo("Kısa metin.");
        assertThat(only.tokenCount()).isEqualTo(counter.countTokens("Kısa metin."));
    }

    @Test
    void overlap_repeatsTheEndOfThePreviousChunk() {
        List<TextChunk> chunks = chunk(new LanguageProfile.Chunk(SIZE, 25), new ParsedSection("A", sentences(60)));

        assertThat(chunks).hasSizeGreaterThan(2);
        for (int i = 1; i < chunks.size(); i++) {
            String content = chunks.get(i).content();
            String firstSentence = content.substring(0, content.indexOf('.') + 1);
            assertThat(chunks.get(i - 1).content()).endsWith(firstSentence);
        }
    }

    @Test
    void zeroOverlap_repeatsNothing() {
        String all = chunk(NO_OVERLAP, new ParsedSection("A", sentences(60))).stream()
                .map(TextChunk::content)
                .collect(Collectors.joining(" "));

        assertThat(all).isEqualTo(sentences(60));
    }

    @Test
    void longSentence_splitsAtWords() {
        String text = words(400);

        List<TextChunk> chunks = chunk(NO_OVERLAP, new ParsedSection("A", text));

        assertWithinLimit(chunks);
        List<String> found = chunks.stream()
                .flatMap(c -> List.of(c.content().split("\\s+")).stream())
                .toList();
        assertThat(found).isEqualTo(List.of(text.split(" ")));
    }

    @Test
    void hugeWord_isCutToFit_andNothingIsLost() {
        String word = IntStream.range(0, 5000).mapToObj(Integer::toString).collect(Collectors.joining());

        List<TextChunk> chunks = chunk(NO_OVERLAP, new ParsedSection("A", word));

        assertWithinLimit(chunks);
        assertThat(joined(chunks)).isEqualTo(word);
    }

    @Test
    void cuttingNeverDamagesCharacters() {
        String word = "ığüşöçİĞ🙂".repeat(300);

        List<TextChunk> chunks = chunk(NO_OVERLAP, new ParsedSection("A", word));

        assertWithinLimit(chunks);
        assertThat(joined(chunks)).isEqualTo(word);
    }

    @Test
    void specialTokenText_isOrdinaryText() {
        List<TextChunk> chunks = chunk(SMALL, new ParsedSection("A", "Önce <|endoftext|> sonra."));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).isEqualTo("Önce <|endoftext|> sonra.");
    }

    @Test
    void overlapThatLeavesNoRoom_isDropped() {
        String shortSentence = "Kısa cümle burada.";
        String longSentence = words(200) + ".";

        List<TextChunk> chunks = chunk(SMALL, new ParsedSection("A",
                shortSentence + " " + longSentence + " " + shortSentence + " " + longSentence));

        assertWithinLimit(chunks);
    }

    @Test
    void lineBreaksAreKept_andBlankLinesRemoved() {
        List<TextChunk> chunks = chunk(SMALL, new ParsedSection("A", "madde bir\nmadde iki\n\nmadde üç"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).isEqualTo("madde bir\nmadde iki\nmadde üç");
    }
}
