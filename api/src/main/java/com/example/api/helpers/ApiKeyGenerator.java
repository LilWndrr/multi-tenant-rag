package com.example.api.helpers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class ApiKeyGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();   // one instance, reused

    public String newKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "rk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] hash(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);   // can't happen on a standard JVM
        }
    }
}
