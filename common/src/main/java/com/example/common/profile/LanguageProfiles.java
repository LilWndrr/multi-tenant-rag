package com.example.common.profile;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// Loads every profiles/*.yaml once at startup; a broken file stops the app instead of failing a request later
@Component
public class LanguageProfiles {

    private static final String LOCATION = "classpath*:profiles/*.yaml";

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Map<String, LanguageProfile> profiles;

    public LanguageProfiles() throws IOException {
        this(LOCATION);
    }

    // tests point this at broken files
    LanguageProfiles(String location) throws IOException {
        Map<String, LanguageProfile> loaded = new TreeMap<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(location)) {
            String fileName = resource.getFilename();
            String id = fileName.substring(0, fileName.length() - ".yaml".length());
            LanguageProfile profile;
            try (InputStream in = resource.getInputStream()) {
                profile = YAML.readValue(in, LanguageProfile.class).withId(id);
            } catch (RuntimeException e) {
                throw new IllegalStateException(fileName + ": cannot parse: " + e.getMessage(), e);
            }
            validate(fileName, profile);
            loaded.put(id, profile);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("No language profiles found at " + location);
        }
        this.profiles = Map.copyOf(loaded);
    }

    public LanguageProfile get(String id) {
        LanguageProfile profile = profiles.get(id);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown language profile: " + id);
        }
        return profile;
    }

    public boolean exists(String id) {
        return id != null && profiles.containsKey(id);
    }

    public Set<String> ids() {
        return profiles.keySet();
    }

    // reranker may be null (keep the retriever's order), but not blank: that would look like a model id to the gateway
    private static void validate(String fileName, LanguageProfile p) {
        if (p.reranker() != null && p.reranker().isBlank()) {
            throw new IllegalStateException(fileName + ": reranker is blank; use null for none");
        }
        require(fileName, "embedding-model", p.embeddingModel());
        require(fileName, "text-search-config", p.textSearchConfig());
        if (p.chunk() == null || p.chunk().size() <= 0 || p.chunk().overlap() < 0
                || p.chunk().overlap() >= p.chunk().size()) {
            throw new IllegalStateException(fileName + ": chunk needs size > 0 and 0 <= overlap < size");
        }
        if (p.prompts() == null) {
            throw new IllegalStateException(fileName + ": prompts is missing");
        }
        require(fileName, "prompts.system", p.prompts().system());
        require(fileName, "prompts.no-answer", p.prompts().noAnswer());
        // without it the model is never told the exact sentence, and refusals can't be recognised
        if (!p.prompts().system().contains(LanguageProfile.Prompts.NO_ANSWER_PLACEHOLDER)) {
            throw new IllegalStateException(fileName + ": prompts.system must contain "
                    + LanguageProfile.Prompts.NO_ANSWER_PLACEHOLDER);
        }
        // an Integer, not an int: Jackson rejects a missing int before this check could name the field
        if (p.embeddingDimensions() == null || p.embeddingDimensions() <= 0) {
            throw new IllegalStateException(fileName + ": embedding-dimensions is missing or not positive");
        }
        if (p.retrieval() == null || !between0And1(p.retrieval().minVectorSimilarity())
                || !between0And1(p.retrieval().minTextScore())) {
            throw new IllegalStateException(fileName + ": retrieval needs min-vector-similarity and min-text-score, each 0 to 1");
        }
    }

    private static boolean between0And1(Double value) {
        return value != null && value >= 0 && value <= 1;
    }

    private static void require(String fileName, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(fileName + ": " + field + " is missing");
        }
    }
}
