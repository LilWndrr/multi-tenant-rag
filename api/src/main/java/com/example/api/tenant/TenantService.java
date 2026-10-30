package com.example.api.tenant;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;


    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public Tenant create(String name, String lp){
        return tenantRepository.create(name,lp);
    }

    public List<Tenant> findAll(){
      return   tenantRepository.findAll();
    }
}
