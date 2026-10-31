package com.example.api.apiKey;

import com.example.api.helpers.ApiKeyGenerator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ApiKeyService {


    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyGenerator generator = new ApiKeyGenerator();
    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    public CreatedApiKey create (UUID tenantId, String name)  {
        String key = generator.newKey();
        String prefix = key.substring(0, 10);
        ApiKey saved = apiKeyRepository.create(tenantId, prefix, ApiKeyGenerator.hash(key), name);
        return new CreatedApiKey(saved.id(), saved.name(), saved.prefix(), saved.createdAt(), key);
    }


    public boolean revoke (UUID id, UUID tenantId){
        return apiKeyRepository.revoke(id,tenantId);
    }

    public List<ApiKey> getByTenant(UUID tenantId){
        return apiKeyRepository.getByTenant(tenantId);
    }
}
