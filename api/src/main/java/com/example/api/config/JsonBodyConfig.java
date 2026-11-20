package com.example.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

// A request body that isn't valid JSON gets a 400 as before, but the error no longer quotes the body. Jackson's message
// does ("Unrecognized token 'How'"), and Spring logs it: at WARN by default, so a piece of a malformed question
// reached the log. Questions and document text never go in a log (docs/decisions.md, Task 26).
@Configuration
public class JsonBodyConfig {

    static final String NOT_READABLE = "The request body is not valid JSON for this endpoint";

    @Bean
    JacksonJsonHttpMessageConverter jsonConverterWithoutBodyInErrors(JsonMapper jsonMapper) {
        return new JacksonJsonHttpMessageConverter(jsonMapper) {
            @Override
            public Object read(ResolvableType type, HttpInputMessage inputMessage, Map<String, Object> hints)
                    throws IOException {
                try {
                    return super.read(type, inputMessage, hints);
                } catch (HttpMessageNotReadableException e) {
                    throw withoutBody(inputMessage);
                }
            }

            @Override
            protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage) throws IOException {
                try {
                    return super.readInternal(clazz, inputMessage);
                } catch (HttpMessageNotReadableException e) {
                    throw withoutBody(inputMessage);
                }
            }
        };
    }

    // no cause: its message is the one that quotes the body
    private static HttpMessageNotReadableException withoutBody(HttpInputMessage inputMessage) {
        return new HttpMessageNotReadableException(NOT_READABLE, inputMessage);
    }
}
