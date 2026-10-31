package com.example.api.tenant;

import com.example.api.apiKey.ApiKey;
import com.example.api.apiKey.ApiKeyService;
import com.example.api.apiKey.CreateApiKeyRequest;
import com.example.api.apiKey.CreatedApiKey;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final ApiKeyService apiKeyService;

    public TenantController(TenantService tenantService, ApiKeyService apiKeyService) {
        this.tenantService = tenantService;

        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    public ResponseEntity<Tenant> create(@RequestBody TenantCreateRequest request){
        if (request.name() == null || request.name().isBlank()
                || !("en".equals(request.languageProfile()) || "tr".equals(request.languageProfile()))) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(tenantService.create(request.name(),request.languageProfile()));
    }
    @GetMapping
    public ResponseEntity<List<Tenant>> findAll(){
        return ResponseEntity.ok(tenantService.findAll());
    }


    @PostMapping("/{tenantId}/api-keys")
    public ResponseEntity<CreatedApiKey> createApiKey(@PathVariable UUID tenantId,
                                                      @RequestBody CreateApiKeyRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(apiKeyService.create(tenantId, request.name()));
    }

    @PostMapping("{tenantId}/api-keys/{keyId}/revoke")
    public ResponseEntity<Void> revokeApiKey (@PathVariable UUID tenantId, @PathVariable UUID keyId){
        return apiKeyService.revoke(keyId,tenantId)? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
    @GetMapping("{tenantId}/api-keys")
    public ResponseEntity<List<ApiKey>> getApiKeyByTenant(@PathVariable UUID tenantId){
        return ResponseEntity.ok(apiKeyService.getByTenant(tenantId));
    }


    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> duplicateName() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Tenant name already exists");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> unknownTenant() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Tenant not found"));
    }
}
