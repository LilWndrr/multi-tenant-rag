package com.example.api.answer;

import com.example.api.retrieve.RetrievedChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Citations {

    // [1], [1, 3] and [1][2]; the last is two matches
    private static final Pattern MARKER = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)]");

    private Citations() {
    }

    // In order of first mention, each source once. A number with no source behind it (the model wrote [9] for six
    // sources) is dropped rather than pointing at the wrong chunk.
    static List<Answer.Citation> of(String text, List<RetrievedChunk> sources) {
        Set<Integer> numbers = new LinkedHashSet<>();
        Matcher m = MARKER.matcher(text);
        while (m.find()) {
            for (String n : m.group(1).split(",")) {
                try {
                    numbers.add(Integer.parseInt(n.trim()));
                } catch (NumberFormatException tooLong) {
                    // digits beyond int: not a source number
                }
            }
        }
        List<Answer.Citation> citations = new ArrayList<>();
        for (int n : numbers) {
            if (n >= 1 && n <= sources.size()) {
                citations.add(new Answer.Citation(n, sources.get(n - 1)));
            }
        }
        return citations;
    }

    // The prompt asks for exactly the no-answer sentence. Case, surrounding spaces, quotes and the final full stop
    // are forgiven; anything more is an answer.
    static boolean isNoAnswer(String text, String noAnswer) {
        return normalise(text).equals(normalise(noAnswer));
    }

    private static String normalise(String s) {
        return s.strip().replaceAll("^[\"'“”«]+|[\"'“”»]+$", "").replaceAll("[.!。]+$", "").strip().toLowerCase(Locale.ROOT);
    }
}
