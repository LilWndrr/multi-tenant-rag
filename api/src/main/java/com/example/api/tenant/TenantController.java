package com.example.api.tenant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController( TenantService tenantService) {
        this.tenantService = tenantService;

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


    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> duplicateName() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Tenant name already exists");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
