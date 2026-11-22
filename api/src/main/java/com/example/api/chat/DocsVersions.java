package com.example.api.chat;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// tenants.docs_version, which a trigger changes in every transaction that changes the tenant's chunks (V7)
@Repository
public class DocsVersions {

    private final JdbcClient jdbc;

    public DocsVersions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long of(UUID tenantId) {
        return jdbc.sql("select docs_version from tenants where id = :id")
                .param("id", tenantId)
                .query(Long.class)
                .single();
    }
}
