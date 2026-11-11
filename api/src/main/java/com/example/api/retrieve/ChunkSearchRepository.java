package com.example.api.retrieve;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

// chunks, chunk_embeddings and documents have row-level security: call these only inside tenantTransactions.run(...).
@Repository
public class ChunkSearchRepository {

    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._:/-]+");

    private final JdbcClient jdbc;

    public ChunkSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }


    public List<RetrievedChunk> nearest(String model, int dimensions, float[] query, int limit) {

        jdbc.sql("set local hnsw.iterative_scan = relaxed_order").update();
        return jdbc.sql(nearestSql(model, dimensions))
                .param("query", Arrays.toString(query))
                .param("limit", limit)
                .query((rs, n) -> new RetrievedChunk(
                        rs.getLong("chunk_id"),
                        rs.getObject("document_id", UUID.class),
                        rs.getString("file_name"),
                        rs.getString("heading_path"),
                        rs.getString("content"),
                        (float) (1 - rs.getDouble("distance"))))
                .list();
    }


    static String nearestSql(String model, int dimensions) {
        if (!MODEL_ID.matcher(model).matches()) {
            throw new IllegalArgumentException("unexpected characters in embedding model id: " + model);
        }
        String distance = "e.embedding::halfvec(%d) <=> cast(:query as halfvec(%d))".formatted(dimensions, dimensions);
        return """
                select chunk_id, document_id, file_name, heading_path, content, distance
                from (
                    select c.id as chunk_id, c.document_id, d.file_name, c.heading_path, c.content, %s  as distance
                    from chunk_embeddings e
                    join chunks c on c.id = e.chunk_id
                    join documents d on d.id = c.document_id
                    where e.model = '%s'
                    order by %s
                    limit :limit
                ) nearest
                order by distance
                """.formatted(distance, model, distance);
    }
}
