CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE chunks (
    id         TEXT PRIMARY KEY,
    path       TEXT        NOT NULL,
    project    TEXT        NOT NULL,
    language   TEXT        NOT NULL,
    start_line INT         NOT NULL,
    end_line   INT         NOT NULL,
    file_hash  TEXT        NOT NULL,
    content    TEXT        NOT NULL,
    embedding  vector(384) NOT NULL
);

CREATE INDEX idx_chunks_path ON chunks (path);
CREATE INDEX idx_chunks_language ON chunks (language);
CREATE INDEX idx_chunks_project ON chunks (project);
-- approximate nearest neighbour search on cosine distance
CREATE INDEX idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);

-- which version of each file is in the index, so re-ingesting skips unchanged files
CREATE TABLE indexed_files (
    path       TEXT PRIMARY KEY,
    file_hash  TEXT        NOT NULL,
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- version changes whenever indexed content changes; cached answers are keyed on it
CREATE TABLE index_state (
    id              INT PRIMARY KEY CHECK (id = 1),
    version         BIGINT NOT NULL,
    embedding_model TEXT
);

INSERT INTO index_state (id, version) VALUES (1, 0);
