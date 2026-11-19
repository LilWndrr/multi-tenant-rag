package com.example.api.answer;

import com.example.api.retrieve.RetrievedChunk;
import com.example.common.model.ChatMessage;
import com.example.common.model.Role;
import com.example.common.profile.LanguageProfile;

import java.util.List;
import java.util.regex.Pattern;

// The messages sent to the chat model. What stays the same comes first, so the provider can cache it: the system
// message is the profile's prompt, identical for every question; the user message holds the numbered sources, then
// the question last. Sources and question are wrapped in tags, so the model can tell quoted data from instructions.
final class AnswerPrompt {

    // a chunk or a question containing "</source>" must not be able to end its block early and look like our markup
    private static final Pattern OUR_TAGS = Pattern.compile("<(/?)(sources?|question)\\b", Pattern.CASE_INSENSITIVE);

    private AnswerPrompt() {
    }

    static List<ChatMessage> build(LanguageProfile profile, String question, List<RetrievedChunk> sources) {
        StringBuilder user = new StringBuilder("<sources>\n");
        for (int i = 0; i < sources.size(); i++) {
            RetrievedChunk source = sources.get(i);
            user.append("<source id=\"").append(i + 1)
                    .append("\" file=\"").append(attribute(source.fileName()))
                    .append("\" heading=\"").append(attribute(source.headingPath()))
                    .append("\">\n")
                    .append(text(source.content()))
                    .append("\n</source>\n");
        }
        user.append("</sources>\n\n<question>\n").append(text(question)).append("\n</question>");
        return List.of(
                new ChatMessage(Role.SYSTEM, profile.prompts().systemWithNoAnswer()),
                new ChatMessage(Role.USER, user.toString()));
    }

    static String text(String value) {
        return OUR_TAGS.matcher(value).replaceAll("&lt;$1$2");
    }

    private static String attribute(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
                .replace('\n', ' ');
    }
}
