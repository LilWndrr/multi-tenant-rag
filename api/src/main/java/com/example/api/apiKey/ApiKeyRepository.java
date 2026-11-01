package com.example.api.apiKey;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApiKeyRepository {

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ApiKey create (UUID tenantId,String prefix, byte[] keyHash, String name ){

        return jdbc.sql("""
        insert into api_keys (tenant_id,prefix,key_hash,name)
        values  (:tenantId, :prefix, :keyHash, :name)
        returning id, name, prefix, created_at, last_used_at, revoked_at
        """).param("tenantId",tenantId).param("prefix",prefix)
                .param("keyHash",keyHash)
                .param("name",name)
                .query(ApiKey.class)
                .single();
    }

    public List<ApiKey> getByTenant(UUID tenantId){
        return jdbc.sql("""
        select id,name,prefix,created_at,last_used_at,revoked_at from api_keys
        where tenant_id = :tenantId
        order by created_at desc 
""").param("tenantId",tenantId)
                .query(ApiKey.class).list();
    }

    public boolean revoke (UUID id, UUID tenantId){
        return jdbc.sql("""
        update api_keys set revoked_at = coalesce(revoked_at,now())
        where id = :id and tenant_id = :tenantId
        """).param("id",id).param("tenantId",tenantId).update()==1;

    }

    public Optional<AuthenticatedKey> getApiKeyByHash (byte[] hashKey){
    return     jdbc.sql("""
        select k.id as api_key_id, k.tenant_id, t.language_profile
        from api_keys k
        join tenants t on t.id = k.tenant_id
        where k.key_hash = :hash and k.revoked_at is null
        """)
            .param("hash",hashKey)
            .query(AuthenticatedKey.class).optional();
    }
}
