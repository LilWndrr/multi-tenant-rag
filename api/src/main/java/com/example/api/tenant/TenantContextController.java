package com.example.api.tenant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class TenantContextController {

    private final TenantContext tenantContext;


    public TenantContextController(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }
    @GetMapping("/context")
    public ResponseEntity<TenantContextResponse> tenantContextCheck(){
        return ResponseEntity.ok(new TenantContextResponse(tenantContext.getTenantId(), tenantContext.getLanguageProfile()));
    }
}
