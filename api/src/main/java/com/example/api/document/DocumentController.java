package com.example.api.document;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/v1/documents")
public class DocumentController {


    private final DocumentService documentService;


    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<DocumentSaveResponse> upload (@RequestParam("file")MultipartFile file){
        return toResponse(documentService.saveDocument(file));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return documentService.deleteDocument(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PutMapping("/{id}")
    public ResponseEntity<DocumentSaveResponse> reUpload(@PathVariable UUID id,@RequestParam("file") MultipartFile file){
       return documentService.changeFileIfDifferent(id, file)
               .map(this::toResponse)
               .orElseGet(() -> ResponseEntity.notFound().build());
    }


    private ResponseEntity<DocumentSaveResponse> toResponse(DocumentUploadResult result) {
        if (result.queued()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .location(URI.create("/v1/documents/" + result.document().id()))
                    .body(result.document());
        }
        return ResponseEntity.ok(result.document());
    }





}
