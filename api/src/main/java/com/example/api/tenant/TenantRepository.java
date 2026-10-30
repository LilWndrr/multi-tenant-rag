package com.example.api.tenant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TenantRepository {

    private final JdbcClient jdbc;


    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Tenant create(String name, String languageProfile){
        return jdbc.sql(""" 
            insert into tenants (name,language_profile)
            values (:name,:lp)
            returning id,name,language_profile,created_at""")
                .param("name",name)
                .param("lp",languageProfile)
            .query(Tenant.class)
                .single();
    }

    public List<Tenant> findAll(){
        return jdbc.sql("""
        select id,name,language_profile,created_at from tenants order by created_at
        """).query(Tenant.class).list();
    }
}
