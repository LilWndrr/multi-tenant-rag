package com.example.worker;

import com.example.common.tenant.TenantTransactions;
import com.example.worker.ingestion.IngestionListener;
import com.example.worker.ingestion.IngestionPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// The real listener against a real queue and DLQ (LocalStack, same init.sh as compose); only the pipeline is replaced.
// Tests don't run in parallel, so stubbing the shared pipeline mock before sending is safe.
// Retries wait retry-delay-seconds (1 in tests) x the attempt number, so the 3-attempt test takes seconds.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IngestionListenerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @MockitoBean
    IngestionPipeline pipeline;

    @Autowired
    IngestionListener listener;
    @Autowired
    SqsClient sqs;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TenantTransactions tenantTransactions;

    // a mock returns false by default, which would mean "replaced by a newer version" for every message
    @BeforeEach
    void pipelineSavesByDefault() {
        when(pipeline.process(any(), any(), any())).thenReturn(true);
    }

    @Test
    void alwaysFailingDocument_gets3Attempts_thenFailedWithTheError_andTheMessageMovesToTheDlq() {
        UUID tenantId = newTenant();
        UUID documentId = newDocument(tenantId, "queued");
        doThrow(new RuntimeException("parse failed")).when(pipeline).process(any(), any(), any());

        send(documentId, tenantId);

        // the DLQ first: a 4th attempt could only happen before the message moves, so afterwards the jobs are final
        String dlqBody = awaitMessageInDlq(documentId);
        assertThat(dlqBody).contains(tenantId.toString());

        assertThat(jobs(tenantId, documentId)).hasSize(3).allSatisfy(job -> {
            assertThat(job.get("status")).isEqualTo("failed");
            assertThat(job.get("error")).isEqualTo("parse failed");
            assertThat(job.get("finished_at")).isNotNull();
        });
        assertThat(jobs(tenantId, documentId)).extracting(job -> job.get("attempt")).containsExactly(1, 2, 3);
        assertThat(document(tenantId, documentId).get("status")).isEqualTo("failed");
        assertThat(document(tenantId, documentId).get("error")).isEqualTo("parse failed");
        // an untagged exception: the pipeline only tags download, parse and embedding failures
        assertThat(document(tenantId, documentId).get("error_code")).isEqualTo("internal_error");
    }

    @Test
    void workingDocument_getsOneSucceededJob_isReady_andTheMessageIsDeleted() {
        UUID tenantId = newTenant();
        UUID documentId = newDocument(tenantId, "queued");

        send(documentId, tenantId);

        awaitDocumentStatus(tenantId, documentId, "ready");
        awaitQueueEmpty();
        verify(pipeline).process(eq(documentId), eq(tenantId), any());
        assertThat(jobs(tenantId, documentId)).singleElement().satisfies(job -> {
            assertThat(job.get("status")).isEqualTo("succeeded");
            assertThat(job.get("attempt")).isEqualTo(1);
            assertThat(job.get("error")).isNull();
            assertThat(job.get("finished_at")).isNotNull();
        });
    }

    @Test
    void failureThenSuccess_keepsBothAttempts_andEndsReady() {
        UUID tenantId = newTenant();
        UUID documentId = newDocument(tenantId, "queued");
        doThrow(new RuntimeException("temporary")).doReturn(true).when(pipeline).process(any(), any(), any());

        send(documentId, tenantId);

        awaitDocumentStatus(tenantId, documentId, "ready");
        awaitQueueEmpty();
        assertThat(jobs(tenantId, documentId)).extracting(job -> job.get("status")).containsExactly("failed", "succeeded");
        assertThat(document(tenantId, documentId).get("error")).isNull();
    }

    @Test
    void documentAlreadyReady_isSkipped_andTheMessageIsDeleted() {
        UUID tenantId = newTenant();
        UUID documentId = newDocument(tenantId, "ready");

        send(documentId, tenantId);   // SQS delivers at least once, so a repeat must do nothing

        awaitQueueEmpty();
        verify(pipeline, never()).process(any(), any(), any());
        assertThat(jobs(tenantId, documentId)).isEmpty();
    }

    @Test
    void unreadableMessage_movesToTheDlq_withoutStoppingTheListener() {
        String marker = UUID.randomUUID().toString();

        send("not json " + marker);
        awaitMessageInDlq(marker);

        // the listener thread survived: a normal message after it still goes through
        UUID tenantId = newTenant();
        UUID documentId = newDocument(tenantId, "queued");
        send(documentId, tenantId);
        awaitDocumentStatus(tenantId, documentId, "ready");
    }

    // The test plays a second worker: it polls the queue past the timeout (3 s in tests) while the job is running.
    @Test
    void jobLongerThanTheVisibilityTimeout_isNotVisibleToAnotherWorker() throws InterruptedException {
        UUID tenantId = newTenant();
        UUID documentId = newDocument(tenantId, "queued");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            finish.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return true;
        }).when(pipeline).process(any(), any(), any());

        send(documentId, tenantId);
        assertThat(started.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        try {
            long pollUntil = System.nanoTime() + Duration.ofSeconds(7).toNanos();
            while (System.nanoTime() < pollUntil) {
                List<Message> visible = sqs.receiveMessage(r -> r.queueUrl(queueUrl("ingestion"))
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(1)).messages();
                assertThat(visible).noneMatch(message -> message.body().contains(documentId.toString()));
            }
        } finally {
            finish.countDown();
        }

        awaitDocumentStatus(tenantId, documentId, "ready");
        awaitQueueEmpty();
        assertThat(jobs(tenantId, documentId)).extracting(job -> job.get("attempt")).containsExactly(1);
    }

    // A PUT during the job: the pipeline saved nothing, so the document is left for the newer message.
    @Test
    void jobReplacedByANewerVersion_succeeds_butDoesNotMarkTheDocumentReady() {
        UUID tenantId = newTenant();
        UUID documentId = newDocument(tenantId, "queued");
        doAnswer(invocation -> {
            simulatePut(tenantId, documentId);
            return false;
        }).when(pipeline).process(any(), any(), any());

        send(documentId, tenantId);

        await().atMost(TIMEOUT).until(() -> !jobs(tenantId, documentId).isEmpty()
                && "succeeded".equals(jobs(tenantId, documentId).getFirst().get("status")));
        awaitQueueEmpty();
        assertThat(document(tenantId, documentId).get("status")).isEqualTo("queued");
    }

    // The old file is deleted after a PUT, so an outdated job can keep failing; it must not fail the newer version.
    @Test
    void outdatedJobFailingEveryAttempt_doesNotMarkTheNewerVersionFailed() {
        UUID tenantId = newTenant();
        UUID documentId = newDocument(tenantId, "queued");
        doAnswer(invocation -> {
            simulatePut(tenantId, documentId);
            throw new RuntimeException("old file is gone");
        }).when(pipeline).process(any(), any(), any());

        send(documentId, tenantId);

        awaitMessageInDlq(documentId);
        assertThat(jobs(tenantId, documentId)).hasSize(3)
                .allSatisfy(job -> assertThat(job.get("status")).isEqualTo("failed"));
        assertThat(document(tenantId, documentId).get("status")).isEqualTo("queued");
        assertThat(document(tenantId, documentId).get("error")).isNull();
    }

    // what the API's PUT does to the row: a new key, back to queued
    private void simulatePut(UUID tenantId, UUID documentId) {
        tenantTransactions.run(tenantId, () -> jdbc.sql("""
                        update documents set s3_key = :key, status = 'queued', error = null where id = :id
                        """)
                .param("key", tenantId + "/" + documentId + "/" + UUID.randomUUID())
                .param("id", documentId)
                .update());
    }

    @Test
    void listenerStoppedAndStartedAgain_stillProcessesMessages() {
        listener.stop();
        listener.start();

        UUID tenantId = newTenant();
        UUID documentId = newDocument(tenantId, "queued");
        send(documentId, tenantId);

        awaitDocumentStatus(tenantId, documentId, "ready");
    }

    private UUID newTenant() {
        // tenants has no row-level security
        return jdbc.sql("insert into tenants (name, language_profile) values (:name, 'en') returning id")
                .param("name", "t-" + UUID.randomUUID())
                .query(UUID.class)
                .single();
    }

    private UUID newDocument(UUID tenantId, String status) {
        return tenantTransactions.run(tenantId, () -> jdbc.sql("""
                        insert into documents (tenant_id, file_name, content_type, size_bytes, checksum, s3_key, status)
                        values (:tenantId, 'a.md', 'text/markdown', 10, decode(md5(random()::text), 'hex'), 'k', :status)
                        returning id
                        """)
                .param("tenantId", tenantId)
                .param("status", status)
                .query(UUID.class)
                .single());
    }

    private void send(UUID documentId, UUID tenantId) {
        send("{\"documentId\":\"" + documentId + "\",\"tenantId\":\"" + tenantId + "\"}");
    }

    private void send(String body) {
        sqs.sendMessage(r -> r.queueUrl(queueUrl("ingestion")).messageBody(body));
    }

    private String queueUrl(String name) {
        return sqs.getQueueUrl(r -> r.queueName(name)).queueUrl();
    }

    private String awaitMessageInDlq(UUID documentId) {
        return awaitMessageInDlq(documentId.toString());
    }

    private String awaitMessageInDlq(String text) {
        List<Message> seen = new ArrayList<>();
        await().atMost(TIMEOUT).until(() -> {
            seen.addAll(sqs.receiveMessage(r -> r.queueUrl(queueUrl("ingestion-dlq"))
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)).messages());
            return seen.stream().anyMatch(m -> m.body().contains(text));
        });
        return seen.stream().map(Message::body).filter(body -> body.contains(text)).findFirst().orElseThrow();
    }

    private void awaitDocumentStatus(UUID tenantId, UUID documentId, String status) {
        await().atMost(TIMEOUT).until(() -> status.equals(document(tenantId, documentId).get("status")));
    }

    // Both counts: "not visible" covers a message the listener has received but not deleted yet.
    private void awaitQueueEmpty() {
        await().atMost(TIMEOUT).until(() -> {
            Map<QueueAttributeName, String> counts = sqs.getQueueAttributes(r -> r.queueUrl(queueUrl("ingestion"))
                    .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
            return counts.values().stream().allMatch("0"::equals);
        });
    }

    private Map<String, Object> document(UUID tenantId, UUID documentId) {
        return tenantTransactions.run(tenantId, () -> jdbc.sql("select status, error, error_code from documents where id = :id")
                .param("id", documentId)
                .query()
                .singleRow());
    }

    private List<Map<String, Object>> jobs(UUID tenantId, UUID documentId) {
        return tenantTransactions.run(tenantId, () -> jdbc.sql("""
                        select attempt, status, error, finished_at from ingestion_jobs
                        where document_id = :documentId order by attempt
                        """)
                .param("documentId", documentId)
                .query()
                .listOfRows());
    }
}
