package com.example.worker.chunking;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

@Component
public class TokenCounter {

    private final Encoding encoding =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    // Ordinary counting: a document containing text like <|endoftext|> must not throw.
    public int countTokens(String text) {
        return encoding.countTokensOrdinary(text);
    }
}
