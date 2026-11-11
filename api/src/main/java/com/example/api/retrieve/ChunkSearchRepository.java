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


    public List<RetrievedChunk> matching(String textSearchConfig, String question, int limit) {
        String parsed = jdbc.sql("select cast(websearch_to_tsquery(cast(:config as regconfig), :question) as text)")
                .param("config", textSearchConfig)
                .param("question", question)
                .query(String.class)
                .single();
        TsQueries.Parts parts = TsQueries.split(parsed);
        if (parts.isEmpty()) {
            return List.of();   // only stop words or exclusions: nothing to search for
        }
        String sql = parts.excluded().isEmpty() ? MATCHING_SQL.formatted("") : MATCHING_SQL.formatted(EXCLUDING);
        var query = jdbc.sql(sql)
                .param("parts", String.join("\n", parts.wanted()))
                .param("limit", limit);
        if (!parts.excluded().isEmpty()) {
            query = query.param("excluded", parts.anyExcluded());
        }
        return query.query((rs, n) -> new RetrievedChunk(
                        rs.getLong("chunk_id"),
                        rs.getObject("document_id", UUID.class),
                        rs.getString("file_name"),
                        rs.getString("heading_path"),
                        rs.getString("content"),
                        rs.getFloat("score")))
                .list();
    }


    static final String MATCHING_SQL = """
            with parts as (
                select cast(p as tsquery) as part from unnest(string_to_array(:parts, chr(10))) p
            ),
            total as (
                select count(*)::float8 as chunks from chunks
            ),
            weighted as (
                select parts.part, ln(1 + (total.chunks - counts.df + 0.5) / (counts.df + 0.5)) as weight
                from parts, total,
                     lateral (select count(*)::float8 as df from chunks c where c.tsv @@ parts.part) counts
                where counts.df > 0
            ),
            question as (
                select sum(weight) as weight from weighted
            )
            select c.id as chunk_id, c.document_id, d.file_name, c.heading_path, c.content,
                   sum(w.weight) / max(question.weight) as score
            from chunks c
            join documents d on d.id = c.document_id
            join weighted w on c.tsv @@ w.part
            cross join question
            %s
            group by c.id, c.document_id, d.file_name, c.heading_path, c.content
            order by score desc, c.id
            limit :limit
            """;

    private static final String EXCLUDING = "where not c.tsv @@ cast(:excluded as tsquery)";

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
