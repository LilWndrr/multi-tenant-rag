package com.example.api.document;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;

@RestController
@RequestMapping("/v1/documents")
public class DocumentController {


    private final DocumentService documentService;


    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<DocumentSaveResponse> upload (@RequestParam("file")MultipartFile file){
        DocumentUploadResult result = documentService.saveDocument(file);


        if(result.created()){
            return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/v1/documents/" + result.document().id())).body(result.document());
        }
        return ResponseEntity.ok(result.document());
    }





}
