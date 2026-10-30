package com.example.api.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class AdminTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final byte[] expectedToken;

    public AdminTokenFilter(String token) {
        if (token == null || token.isBlank()) {
            // fail fast: no token configured = app refuses to start
            throw new IllegalStateException("rag.admin.token (ADMIN_TOKEN) must be set");
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");   // e.g. "Bearer dev-admin-token"

        if (header == null || !header.startsWith(PREFIX)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;                                            // stop: controller never runs
        }

        byte[] given = header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, given)) {    // constant-time comparison
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        chain.doFilter(request, response);                     // token OK → continue to controller
    }
}