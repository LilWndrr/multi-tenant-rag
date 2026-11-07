package com.example.worker.chunking;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.UUID;


@Repository
public class ChunkRepository {

    private final JdbcClient jdbc;

    public ChunkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // chunk_embeddings rows go too, through their foreign key's cascade
    public void deleteByDocument(UUID documentId) {
        jdbc.sql("delete from chunks where document_id = :id")
                .param("id", documentId)
                .update();
    }

    // One statement, so a chunk can't be saved without its vector
    public void insertChunkAndVectorRow(UUID tenantId, UUID documentId, TextChunk chunk,
                                        String tsConfig, String model, float[] vector) {
        jdbc.sql("""
                with chunk as (
                    insert into chunks (tenant_id, document_id, ordinal, content, heading_path, token_count, ts_config)
                    values (:tenantId, :documentId, :ordinal, :content, :headingPath, :tokenCount, cast(:tsConfig as regconfig))
                    returning id
                )
                insert into chunk_embeddings (chunk_id, tenant_id, model, embedding)
                select chunk.id, :tenantId, :model, cast(:embedding as vector)
                from chunk
                """)
                .param("tenantId", tenantId)
                .param("documentId", documentId)
                .param("ordinal", chunk.ordinal())
                .param("content", chunk.content())
                .param("headingPath", chunk.headingPath())
                .param("tokenCount", chunk.tokenCount())
                .param("tsConfig", tsConfig)
                .param("model", model)
                .param("embedding", Arrays.toString(vector))   // pgvector reads the text form: [0.1, 0.2]
                .update();
    }
}
