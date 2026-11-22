package com.example.api.chat;

// What /v1/chat sends after the tokens; cached is true when it came from the answer cache
public record ChatReply(String text, ChatEvents.Citations citations, boolean cached) {

    @Override
    public String toString() {
        return "ChatReply[" + text.length() + " chars, " + citations.citations().size() + " citations, cached=" + cached + "]";
    }
}
