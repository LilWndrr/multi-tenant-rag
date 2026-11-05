package com.example.api;

import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

// Same split as production: Flyway runs as the container's superuser and creates rag_app in V3;
// the app logs in as rag_app, so row-level security applies in every test.
// (@ServiceConnection would give both the superuser, and the policies would never be checked.)
// The connection details are beans, not DynamicPropertyRegistrar entries: with a real server (RANDOM_PORT)
// Tomcat starts before the registrar runs, and the API key filter already needs the database then.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String APP_USER = "rag_app";
    static final String APP_PASSWORD = "rag_app_test";

    @Bean
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    // Same image, region and init script as compose.yaml, so tests see the same bucket, queue and DLQ.
    @Bean
    LocalStackContainer localstack() {
        return new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
                .withServices("s3", "sqs")
                .withEnv("AWS_DEFAULT_REGION", "eu-central-1")
                .withCopyFileToContainer(MountableFile.forHostPath(initScript(), 0755),
                        "/etc/localstack/init/ready.d/init.sh")
                // like the compose healthcheck: ready once the init script has created the last queue
                .waitingFor(Wait.forSuccessfulCommand("awslocal sqs get-queue-url --queue-name ingestion")
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    @Bean
    JdbcConnectionDetails appConnection(PostgreSQLContainer postgres) {
        return new JdbcConnectionDetails() {
            public String getJdbcUrl() { return postgres.getJdbcUrl(); }
            public String getUsername() { return APP_USER; }
            public String getPassword() { return APP_PASSWORD; }
        };
    }

    @Bean
    FlywayConnectionDetails flywayConnection(PostgreSQLContainer postgres) {
        return new FlywayConnectionDetails() {
            public String getJdbcUrl() { return postgres.getJdbcUrl(); }
            public String getUsername() { return postgres.getUsername(); }
            public String getPassword() { return postgres.getPassword(); }
        };
    }

    @Bean
    DynamicPropertyRegistrar localstackProperties(LocalStackContainer localstack) {
        return registry -> registry.add("rag.aws.endpoint", () -> localstack.getEndpoint().toString());
    }

    // Maven runs tests from the api folder, some IDE setups from the repo root.
    private static Path initScript() {
        Path fromModule = Path.of("../docker/localstack/init.sh");
        return Files.exists(fromModule) ? fromModule : Path.of("docker/localstack/init.sh");
    }
}
