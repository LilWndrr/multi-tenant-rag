package com.example.worker.ingestion;

import com.example.common.profile.LanguageProfile;
import com.example.common.profile.LanguageProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class TenantProfileRepository {

    private final JdbcClient jdbc;
    private final LanguageProfiles profiles;

    public TenantProfileRepository(JdbcClient jdbc, LanguageProfiles profiles) {
        this.jdbc = jdbc;
        this.profiles = profiles;
    }

    public LanguageProfile findFor(UUID tenantId) {
        String profileId = jdbc.sql("select language_profile from tenants where id = :id")
                .param("id", tenantId)
                .query(String.class)
                .single();
        return profiles.get(profileId);
    }
}