package com.example.api.document;

import com.example.api.tenant.TenantContext;
import com.example.common.ingestion.IngestionMessage;
import com.example.common.tenant.TenantTransactions;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE;

@Service
public class DocumentService {

    // What Tika 4 reports for PDF, DOCX, HTML and Markdown (checked against real files).
    // DOCX needs its .docx name: from the bytes alone Tika only sees "some Office zip" (application/x-tika-ooxml).
    static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/html",
            "text/markdown");

    private final S3Client s3Client;
    private final IngestionQueue ingestionQueue;
    private final TenantContext tenantContext;
    private final TenantTransactions tenantTransactions;
    private final DocumentRepository documentRepository;
    private final String bucketName;
    private final Tika tika = new Tika();

    public DocumentService(S3Client s3Client, IngestionQueue ingestionQueue, TenantContext tenantContext,
                           TenantTransactions tenantTransactions, DocumentRepository documentRepository,
                           @Value("${rag.aws.s3.bucket}") String bucketName) {
        this.s3Client = s3Client;
        this.ingestionQueue = ingestionQueue;
        this.tenantContext = tenantContext;
        this.tenantTransactions = tenantTransactions;
        this.documentRepository = documentRepository;
        this.bucketName = bucketName;
    }

    // Not @Transactional: S3 and SQS can't take part in a database transaction, and the SQS send
    // must happen after the row has committed, or the worker could get the message before the row exists.
    public DocumentUploadResult saveDocument(MultipartFile multipartFile) {
        if (multipartFile.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "file is empty");
        }
        UUID tenantId = tenantContext.getTenantId();
        byte[] bytes = readBytes(multipartFile);
        String fileName = fileNameOf(multipartFile);

        // from the bytes, with the name only as a hint; the client's Content-Type is never trusted
        String contentType = tika.detect(bytes, fileName);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "unsupported file type: " + contentType);
        }

        byte[] checksum = sha256(bytes);
        var existing = tenantTransactions.run(tenantId, () -> documentRepository.findByChecksum(checksum));
        if (existing.isPresent()) {
            return new DocumentUploadResult(existing.get(), false);
        }

        UUID documentId = UUID.randomUUID();
        String s3Key = tenantId + "/" + documentId;   // never the file name: it comes from the client
        s3Client.putObject(request -> request
                        .bucket(bucketName)
                        .key(s3Key)
                        .contentType(contentType)
                        .checksumSHA256(Base64.getEncoder().encodeToString(checksum)),   // S3 rejects the upload if it arrived damaged
                RequestBody.fromBytes(bytes));

        try {
            tenantTransactions.run(tenantId, () -> documentRepository.create(documentId, tenantId, fileName, contentType,
                    multipartFile.getSize(), checksum, s3Key, DocumentProcessStatus.queued));
        } catch (DuplicateKeyException sameFileUploadedConcurrently) {
            // two identical uploads raced past the check above; the other one won, so drop our copy and return theirs
            s3Client.deleteObject(request -> request.bucket(bucketName).key(s3Key));
            var winner = tenantTransactions.run(tenantId, () -> documentRepository.findByChecksum(checksum)).orElseThrow();
            return new DocumentUploadResult(winner, false);
        }

        try {
            ingestionQueue.publish(new IngestionMessage(documentId, tenantId));
        } catch (SdkException e) {
            // see docs/decisions.md: no outbox, so a failed send fails the document and the client is told to retry
            tenantTransactions.run(tenantId, () -> documentRepository.markFailed(documentId, "could not queue for ingestion"));
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "could not queue the document, try again", e);
        }

        return new DocumentUploadResult(new DocumentSaveResponse(documentId, DocumentProcessStatus.queued), true);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();   // at most 20 MB (spring.servlet.multipart.max-file-size)
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Stored as metadata only. Strips any client-side path ("C:\docs\a.pdf", "../a.pdf").
    private static String fileNameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "upload";
        }
        return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);   // every JVM has it
        }
    }
}
