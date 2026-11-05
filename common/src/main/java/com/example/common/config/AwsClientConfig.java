package com.example.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Configuration
public class AwsClientConfig {

    @Bean
    public S3Client s3Client(@Value("${rag.aws.endpoint:}") String endpoint,
                             @Value("${rag.aws.region}") String region) {
        var builder = S3Client.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(localCredentials())
                    .forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    public SqsClient sqsClient(@Value("${rag.aws.endpoint:}") String endpoint,
                               @Value("${rag.aws.region}") String region) {
        var builder = SqsClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).credentialsProvider(localCredentials());
        }
        return builder.build();
    }

    private static StaticCredentialsProvider localCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    }
}
