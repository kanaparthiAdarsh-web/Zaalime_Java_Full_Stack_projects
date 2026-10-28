package com.zaalime.rag.store;

import com.pgvector.PGvector;
import com.zaalime.rag.ingest.Chunk;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Chunks and their embeddings in PostgreSQL + pgvector, plus the record of which file versions are indexed. */
@Repository
public class ChunkStore {

    public record Hit(Chunk chunk, double similarity) {
    }

    private static final RowMapper<Chunk> CHUNK = (rs, i) -> new Chunk(
            rs.getString("id"), rs.getString("path"), rs.getString("project"), rs.getString("language"),
            rs.getString("content"), rs.getInt("start_line"), rs.getInt("end_line"), rs.getString("file_hash"));

    private final JdbcTemplate jdbc;

    public ChunkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Replaces everything indexed for one file in a single transaction. */
    @Transactional
    public void replaceFile(String path, String fileHash, List<Chunk> chunks, List<float[]> embeddings) {
        jdbc.update("DELETE FROM chunks WHERE path = ?", path);
        jdbc.batchUpdate("""
                        INSERT INTO chunks (id, path, project, language, start_line, end_line, file_hash, content, embedding)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Chunk c = chunks.get(i);
                        ps.setString(1, c.id());
                        ps.setString(2, c.path());
                        ps.setString(3, c.project());
                        ps.setString(4, c.language());
                        ps.setInt(5, c.startLine());
                        ps.setInt(6, c.endLine());
                        ps.setString(7, c.fileHash());
                        ps.setString(8, c.text().replace("\u0000", "")); // Postgres text can't hold NUL
                        ps.setObject(9, new PGvector(embeddings.get(i)));
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                });
        jdbc.update("""
                INSERT INTO indexed_files (path, file_hash) VALUES (?, ?)
                ON CONFLICT (path) DO UPDATE SET file_hash = EXCLUDED.file_hash, indexed_at = now()
                """, path, fileHash);
    }

    @Transactional
    public void removeFile(String path) {
        jdbc.update("DELETE FROM chunks WHERE path = ?", path);
        jdbc.update("DELETE FROM indexed_files WHERE path = ?", path);
    }

    public Map<String, String> indexedFiles() {
        Map<String, String> files = new HashMap<>();
        jdbc.query("SELECT path, file_hash FROM indexed_files", rs -> {
            files.put(rs.getString(1), rs.getString(2));
        });
        return files;
    }

    /** Cosine-similarity search, optionally restricted to languages and / or a project. */
    @Transactional(readOnly = true)
    public List<Hit> search(float[] embedding, int limit, List<String> languages, String project) {
        // HNSW filters after the graph walk; let it look further so filtered queries still fill `limit`
        jdbc.execute("SET LOCAL hnsw.ef_search = 200");
        StringBuilder sql = new StringBuilder(
                "SELECT *, 1 - (embedding <=> ?) AS similarity FROM chunks WHERE true");
        List<Object> args = new ArrayList<>();
        PGvector vector = new PGvector(embedding);
        args.add(vector);
        if (languages != null && !languages.isEmpty()) {
            sql.append(" AND language = ANY (?)");
            args.add(languages.toArray(String[]::new));
        }
        if (project != null && !project.isBlank()) {
            sql.append(" AND project = ?");
            args.add(project);
        }
        sql.append(" ORDER BY embedding <=> ? LIMIT ?");
        args.add(vector);
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new Hit(CHUNK.mapRow(rs, i), rs.getDouble("similarity")),
                args.toArray());
    }

    public List<Chunk> all() {
        return jdbc.query("SELECT id, path, project, language, start_line, end_line, file_hash, content FROM chunks", CHUNK);
    }

    public long count() {
        return jdbc.queryForObject("SELECT count(*) FROM chunks", Long.class);
    }

    // --- index state: a version number that changes whenever the indexed content does ---

    public long version() {
        return jdbc.queryForObject("SELECT version FROM index_state WHERE id = 1", Long.class);
    }

    public void bumpVersion() {
        jdbc.update("UPDATE index_state SET version = version + 1 WHERE id = 1");
    }

    /** Refuses to mix vectors from different embedding models in one index. */
    public void checkEmbeddingModel(String model) {
        String stored = jdbc.queryForObject("SELECT embedding_model FROM index_state WHERE id = 1", String.class);
        if (stored == null) {
            jdbc.update("UPDATE index_state SET embedding_model = ? WHERE id = 1", model);
        } else if (!stored.equals(model)) {
            throw new IllegalStateException("Index was built with '" + stored + "' but the configured embedder is '"
                    + model + "'. Re-create the database and re-ingest.");
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("chunks", count());
        stats.put("files", jdbc.queryForObject("SELECT count(*) FROM indexed_files", Long.class));
        stats.put("index_version", version());
        stats.put("by_language", grouped("language"));
        stats.put("projects", grouped("project"));
        return stats;
    }

    private Map<String, Long> grouped(String column) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT " + column + ", count(*) FROM chunks GROUP BY 1 ORDER BY 2 DESC",
                rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }
}
