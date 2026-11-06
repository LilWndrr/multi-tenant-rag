package com.example.worker.ingestion;

import com.example.common.ingestion.IngestionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Plain SDK loop: Spring Cloud AWS's @SqsListener doesn't list Boot 4.1 as supported (docs/decisions.md).
@Component
public class IngestionListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IngestionListener.class);

    private final SqsClient sqs;
    private final JsonMapper json;
    private final IngestionProcessor processor;   // one message's work: job row, pipeline, statuses
    private final String queueName;
    private final int retryDelaySeconds;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ingestion-listener"));
    private volatile boolean running;
    private String queueUrl;   // only touched by the listener thread

    public IngestionListener(SqsClient sqs, JsonMapper json, IngestionProcessor processor,
                             @Value("${rag.aws.sqs.queue}") String queueName,
                             @Value("${rag.ingestion.retry-delay-seconds}") int retryDelaySeconds) {
        this.sqs = sqs;
        this.json = json;
        this.processor = processor;
        this.queueName = queueName;
        this.retryDelaySeconds = retryDelaySeconds;
    }

    @Override
    public void start() {
        running = true;
        executor.submit(this::pollLoop);
    }

    @Override
    public void stop() {
        running = false;   // the loop finishes its current message and exits
        executor.shutdown();
        try {
            // a long poll can take up to 20 s to return
            if (!executor.awaitTermination(25, TimeUnit.SECONDS)) {
                executor.shutdownNow();   // the unfinished message reappears after its visibility timeout
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        while (running) {
            try {
                if (queueUrl == null) {
                    // here rather than in start(): an SQS outage delays the worker instead of stopping it from booting
                    queueUrl = sqs.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
                }
                // One at a time: the visibility timeout starts at receive, so messages waiting behind
                // a slow one in a batch could time out and use up attempts without ever being tried.
                List<Message> messages = sqs.receiveMessage(r -> r
                                .queueUrl(queueUrl)
                                .maxNumberOfMessages(1)
                                .waitTimeSeconds(20)
                                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
                        .messages();
                for (Message message : messages) {
                    handle(message);
                }
            } catch (Exception e) {
                // SQS unreachable etc.: never let the thread die, back off and poll again
                log.warn("polling {} failed, retrying in 5 s", queueName, e);
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handle(Message message) {
        int attempt = Integer.parseInt(message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT));
        try {
            IngestionMessage body = json.readValue(message.body(), IngestionMessage.class);
            processor.process(body, attempt);   // throws if the attempt failed
            sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (Exception e) {
            log.warn("ingestion attempt {} failed for message {}", attempt, message.messageId(), e);
            // not deleted: SQS redelivers it; after the last receive it moves to the DLQ by itself
            sqs.changeMessageVisibility(r -> r.queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .visibilityTimeout(retryDelaySeconds * attempt));
        }
    }
}
