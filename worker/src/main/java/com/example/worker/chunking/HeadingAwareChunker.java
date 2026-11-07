package com.example.worker.chunking;

import com.example.common.profile.LanguageProfile;
import com.example.worker.document.ParsedDocument;
import com.example.worker.document.ParsedSection;
import org.springframework.stereotype.Component;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class HeadingAwareChunker implements Chunker {

    private final TokenCounter tokens;

    public HeadingAwareChunker(TokenCounter tokens) {
        this.tokens = tokens;
    }

    @Override
    public List<TextChunk> chunk(ParsedDocument document, LanguageProfile.Chunk settings) {
        List<TextChunk> chunks = new ArrayList<>();
        for (ParsedSection section : document.sections()) {
            List<String> pieces = cutIntoPieces(section.text(), settings.size());
            pack(pieces, section.headingPath(), settings, chunks);
        }
        return chunks;
    }

    private List<String> cutIntoPieces(String text, int size) {
        List<String> pieces = new ArrayList<>();
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);

        for (String line : text.split("(?<=\n)")) {
            if (line.isBlank()) {
                continue;
            }
            sentences.setText(line);
            int start = sentences.first();
            for (int end = sentences.next(); end != BreakIterator.DONE; start = end, end = sentences.next()) {
                String sentence = line.substring(start, end);
                if (tokens.countTokens(sentence) <= size) {
                    pieces.add(sentence);
                } else {
                    pieces.addAll(splitLongSentence(sentence, size));
                }
            }
        }
        return pieces;
    }

    private List<String> splitLongSentence(String sentence, int size) {
        List<String> pieces = new ArrayList<>();
        StringBuilder group = new StringBuilder();

        BreakIterator words = BreakIterator.getWordInstance(Locale.ROOT);
        words.setText(sentence);
        int start = words.first();
        for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
            String word = sentence.substring(start, end);

            if (tokens.countTokens(group + word) <= size) {
                group.append(word);
            } else {
                if (!group.isEmpty()) {
                    pieces.add(group.toString());
                    group.setLength(0);
                }
                if (tokens.countTokens(word) <= size) {
                    group.append(word);
                } else {
                    splitByCharacters(word, size, pieces);
                }
            }
        }

        if (!group.isEmpty()) {
            pieces.add(group.toString());
        }
        return pieces;
    }

    private void splitByCharacters(String word, int size, List<String> pieces) {
        if (tokens.countTokens(word) <= size) {
            pieces.add(word);
            return;
        }
        int middle = word.length() / 2;
        if (Character.isLowSurrogate(word.charAt(middle))) {
            middle++;   // don't cut an emoji in half
        }
        splitByCharacters(word.substring(0, middle), size, pieces);
        splitByCharacters(word.substring(middle), size, pieces);
    }

    private void pack(List<String> pieces, String headingPath, LanguageProfile.Chunk settings, List<TextChunk> chunks) {
        List<String> current = new ArrayList<>();

        for (String piece : pieces) {
            if (tokens.countTokens(join(current) + piece) > settings.size()) {
                emit(current, headingPath, chunks);
                current = overlapFrom(current, settings.overlap());

                // overlap + piece may still be too big: the size limit wins
                while (!current.isEmpty() && tokens.countTokens(join(current) + piece) > settings.size()) {
                    current.removeFirst();
                }
            }
            current.add(piece);
        }

        emit(current, headingPath, chunks);
    }


    private List<String> overlapFrom(List<String> finished, int overlap) {
        List<String> kept = new ArrayList<>();
        for (int i = finished.size() - 1; i >= 0; i--) {
            kept.addFirst(finished.get(i));
            if (tokens.countTokens(join(kept)) > overlap) {
                kept.removeFirst();
                break;
            }
        }
        return kept;
    }

    private void emit(List<String> current, String headingPath, List<TextChunk> chunks) {
        String content = join(current).strip();
        if (!content.isEmpty()) {
            chunks.add(new TextChunk(chunks.size(), headingPath, content, tokens.countTokens(content)));
        }
    }

    private String join(List<String> pieces) {
        return String.join("", pieces);
    }


}