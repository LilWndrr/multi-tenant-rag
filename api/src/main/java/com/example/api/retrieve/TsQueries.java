package com.example.api.retrieve;

import java.util.ArrayList;
import java.util.List;


final class TsQueries {

    record Parts(List<String> wanted, List<String> excluded) {

        boolean isEmpty() {
            return wanted.isEmpty();
        }

        String anyExcluded() {
            return String.join(" | ", excluded);
        }
    }

    private TsQueries() {
    }

    static Parts split(String websearchQuery) {
        List<String> wanted = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        if (websearchQuery != null && !websearchQuery.isBlank()) {
            for (String part : websearchQuery.split(" & ")) {
                if (part.startsWith("!")) {
                    excluded.add(part.substring(1));
                } else {
                    wanted.add(part);
                }
            }
        }
        return new Parts(List.copyOf(wanted), List.copyOf(excluded));
    }
}
