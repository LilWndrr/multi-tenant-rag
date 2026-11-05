package com.example.api.document;

import com.example.common.ingestion.IngestionMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

@Component
public class IngestionQueue {

    private final SqsClient sqsClient;
    private final JsonMapper jsonMapper;
    private final String queueName;
    private volatile String queueUrl;

    public IngestionQueue(SqsClient sqsClient, JsonMapper jsonMapper, @Value("${rag.aws.sqs.queue}") String queueName) {
        this.sqsClient = sqsClient;
        this.jsonMapper = jsonMapper;
        this.queueName = queueName;
    }

    // Throws an SdkException when SQS can't be reached; the caller decides what that means for the document.
    public void publish(IngestionMessage message) {
        sqsClient.sendMessage(request -> request
                .queueUrl(queueUrl())
                .messageBody(jsonMapper.writeValueAsString(message)));
    }

    // SQS sends by URL, not name. Looked up on first use rather than at startup,
    // so the app (and every test context) starts even when SQS is down; a failed lookup is retried next time.
    private String queueUrl() {
        String url = queueUrl;
        if (url == null) {
            url = sqsClient.getQueueUrl(request -> request.queueName(queueName)).queueUrl();
            queueUrl = url;
        }
        return url;
    }
}
