package com.example.api.document;

import com.example.api.tenant.TenantContext;
import com.example.common.enums.DocumentProcessStatus;
import com.example.common.ingestion.IngestionMessage;
import com.example.common.tenant.TenantTransactions;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.*;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
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
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

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
        UUID tenantId = tenantContext.getTenantId();
        Upload upload = validate(multipartFile);
        byte[] checksum = upload.checksum();

        var existing = tenantTransactions.run(tenantId, () -> documentRepository.findByChecksum(checksum));
        if (existing.isPresent()) {
            return retryIfFailed(existing.get(), tenantId, checksum);
        }

        UUID documentId = UUID.randomUUID();
        String s3Key = tenantId + "/" + documentId;   // never the file name: it comes from the client
        putObject(s3Key, upload);

        try {
            tenantTransactions.run(tenantId, () -> documentRepository.create(documentId, tenantId, upload.fileName(),
                    upload.contentType(), upload.bytes().length, checksum, s3Key, DocumentProcessStatus.queued));
        } catch (DuplicateKeyException sameFileUploadedConcurrently) {
            // two identical uploads raced past the check above; the other one won, so drop our copy and return theirs
            deleteObject(s3Key);
            var winner = tenantTransactions.run(tenantId, () -> documentRepository.findByChecksum(checksum)).orElseThrow();
            return new DocumentUploadResult(winner, false);
        }

        return publish(documentId, tenantId);
    }


    private DocumentUploadResult retryIfFailed(DocumentSaveResponse existing, UUID tenantId, byte[] checksum) {
        if (existing.status() != DocumentProcessStatus.failed) {
            return new DocumentUploadResult(existing, false);
        }
        boolean requeued = tenantTransactions.run(tenantId, () -> documentRepository.requeueIfFailed(existing.id()));
        if (!requeued) {
            // a concurrent retry got there first and sent the message; return the document as it is now
            var current = tenantTransactions.run(tenantId, () -> documentRepository.findByChecksum(checksum)).orElseThrow();
            return new DocumentUploadResult(current, false);
        }
        return publish(existing.id(), tenantId);
    }

    private DocumentUploadResult publish(UUID documentId, UUID tenantId) {
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


    public Optional<DocumentUploadResult> changeFileIfDifferent(UUID documentId, MultipartFile file) {
        UUID tenantId = tenantContext.getTenantId();
        Upload upload = validate(file);

        var stored = tenantTransactions.run(tenantId, () -> documentRepository.findById(documentId));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        if (Arrays.equals(upload.checksum(), stored.get().checksum())) {
            return Optional.of(retryIfFailed(new DocumentSaveResponse(documentId, stored.get().status()), tenantId, upload.checksum()));
        }

        // a new key per version: if the update below fails, the row still points at the old file, which is untouched
        String oldS3Key = stored.get().s3Key();
        String newS3Key = tenantId + "/" + documentId + "/" + UUID.randomUUID();
        putObject(newS3Key, upload);

        boolean replaced;
        try {
            replaced = tenantTransactions.run(tenantId, () -> documentRepository.replaceFile(documentId, oldS3Key,
                    upload.checksum(), upload.bytes().length, upload.contentType(), upload.fileName(), newS3Key));
        } catch (DuplicateKeyException sameContentAsAnotherDocument) {
            deleteObject(newS3Key);
            throw new ResponseStatusException(CONFLICT, "another document already has this content");
        }
        if (!replaced) {
            deleteObject(newS3Key);
            throw new ResponseStatusException(CONFLICT, "the document changed during the upload, try again");
        }

        deleteObject(oldS3Key);
        return Optional.of(publish(documentId, tenantId));
    }

    private record Upload(byte[] bytes, String fileName, String contentType, byte[] checksum) {}

    private Upload validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "file is empty");
        }
        byte[] bytes = readBytes(file);
        String fileName = fileNameOf(file);

        // from the bytes, with the name only as a hint; the client's Content-Type is never trusted
        String contentType = tika.detect(bytes, fileName);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "unsupported file type: " + contentType);
        }
        return new Upload(bytes, fileName, contentType, sha256(bytes));
    }

    // The row first: a leftover S3 object is harmless, a row pointing at a missing file is not.
    // A job running at the same time fails on the missing row and its retry finds nothing to do.
    public boolean deleteDocument(UUID documentId) {
        UUID tenantId = tenantContext.getTenantId();
        Optional<String> s3Key = tenantTransactions.run(tenantId, () -> documentRepository.delete(documentId));
        if (s3Key.isEmpty()) {
            return false;
        }
        try {
            deleteObject(s3Key.get());
        } catch (SdkException e) {
            // the document is already gone for the client, so this isn't their error; the object is left behind
            log.warn("document {} deleted, but its S3 object could not be removed", documentId, e);
        }
        return true;
    }

    private void putObject(String s3Key, Upload upload) {
        s3Client.putObject(request -> request
                        .bucket(bucketName)
                        .key(s3Key)
                        .contentType(upload.contentType())
                        .checksumSHA256(Base64.getEncoder().encodeToString(upload.checksum())),   // S3 rejects the upload if it arrived damaged
                RequestBody.fromBytes(upload.bytes()));
    }

    private void deleteObject(String s3Key) {
        s3Client.deleteObject(request -> request.bucket(bucketName).key(s3Key));
    }
}
