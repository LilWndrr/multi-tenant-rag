package com.example.worker.document;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Plain unit tests, no containers. The samples in src/test/resources/samples hold nested headings and non-ASCII text (ığüşöçİĞ).
class DocumentParserTest {

    private static final String BODY = "Giriş metni ığüşöçİĞ";
    private static final String NESTED = "Çalışma ayarları değişir";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final List<ParsedSection> EXPECTED_SECTIONS = List.of(
            new ParsedSection("Kurulum", BODY),
            new ParsedSection("Kurulum > Yapılandırma", NESTED),
            new ParsedSection("Kullanım", "Son bölüm"));

    private final DocumentParser parser = new DocumentParser(new TikaDocumentParser(), new MarkdownDocumentParser());

    @Test
    void markdownSample_keepsHeadingPathsAndCharacters() throws IOException {
        assertThat(parseSample("sample.md", "text/markdown").sections()).isEqualTo(EXPECTED_SECTIONS);
    }

    @Test
    void htmlSample_keepsHeadingPathsAndCharacters_andSkipsTheTitle() throws IOException {
        assertThat(parseSample("sample.html", "text/html").sections()).isEqualTo(EXPECTED_SECTIONS);
    }

    @Test
    void docxSample_keepsHeadingPathsAndCharacters() throws IOException {
        assertThat(parseSample("sample.docx", DOCX).sections()).isEqualTo(EXPECTED_SECTIONS);
    }

    // PDFs have no heading structure (docs/decisions.md), so all the text lands in one section with an empty path
    @Test
    void pdfSample_keepsCharacters_withAnEmptyHeadingPath() throws IOException {
        List<ParsedSection> sections = parseSample("sample.pdf", "application/pdf").sections();

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.headingPath()).isEmpty();
            assertThat(section.text()).contains("Kurulum", BODY, NESTED);
        });
    }

    @Test
    void pdfWithoutText_throws() {
        assertThatThrownBy(() -> parseSample("sample-no-text.pdf", "application/pdf"))
                .isInstanceOf(DocumentParseException.class)
                .hasMessage("no text extracted");
    }

    @Test
    void corruptPdf_throws() {
        assertThatThrownBy(() -> parser.parse(utf8("%PDF-1.7 garbage"), "application/pdf"))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("could not parse application/pdf");
    }

    @Test
    void htmlInALegacyEncoding_isDecodedFromItsDeclaredCharset() {
        String html = "<html><head><meta charset=\"windows-1254\"></head><body><h1>Başlık</h1><p>" + BODY + "</p></body></html>";

        var bytes = new ByteArrayInputStream(html.getBytes(Charset.forName("windows-1254")));

        assertThat(parser.parse(bytes, "text/html").sections())
                .containsExactly(new ParsedSection("Başlık", BODY));
    }

    @Test
    void markdown_ignoresHashesInsideCodeFences_andWithoutASpace() {
        String markdown = """
                # Kurulum

                ```bash
                # yorum satırı
                ```

                #etiket
                """;

        List<ParsedSection> sections = parser.parse(utf8(markdown), "text/markdown").sections();

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.headingPath()).isEqualTo("Kurulum");
            assertThat(section.text()).contains("# yorum satırı", "#etiket");
        });
    }

    @Test
    void textBeforeTheFirstHeading_hasAnEmptyPath_andANewHeadingEndsDeeperOnes() {
        String markdown = """
                önsöz

                # A

                bir

                ### C

                iki

                ## B

                üç
                """;

        assertThat(parser.parse(utf8(markdown), "text/markdown").sections()).containsExactly(
                new ParsedSection("", "önsöz"),
                new ParsedSection("A", "bir"),
                new ParsedSection("A > C", "iki"),
                new ParsedSection("A > B", "üç"));
    }

    @Test
    void headingsWithNoText_produceNoSections() {
        assertThatThrownBy(() -> parser.parse(utf8("# Sadece başlık\n"), "text/markdown"))
                .isInstanceOf(DocumentParseException.class)
                .hasMessage("no text extracted");
    }

    // BodyContentHandler, the usual Tika handler, stops at 100,000 characters by default
    @Test
    void longDocument_isNotCutOffAt100000Characters() {
        String paragraph = "<p>" + "ş".repeat(1_000) + "</p>";
        String html = "<html><body><h1>Uzun</h1>" + paragraph.repeat(150) + "<p>son satır</p></body></html>";

        String text = parser.parse(utf8(html), "text/html").sections().getFirst().text();

        assertThat(text.length()).isGreaterThan(150_000);
        assertThat(text).endsWith("son satır");
    }

    @Test
    void documentOverTheCharacterLimit_throws() {
        String markdown = ("x".repeat(1_000) + "\n").repeat(20_001);

        assertThatThrownBy(() -> parser.parse(utf8(markdown), "text/markdown"))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("characters of text");
    }

    private ParsedDocument parseSample(String name, String contentType) throws IOException {
        try (InputStream sample = getClass().getResourceAsStream("/samples/" + name)) {
            assertThat(sample).as(name).isNotNull();
            return parser.parse(sample, contentType);
        }
    }

    private static InputStream utf8(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
