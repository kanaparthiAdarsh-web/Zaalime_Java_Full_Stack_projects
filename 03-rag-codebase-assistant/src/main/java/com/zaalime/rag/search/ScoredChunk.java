package com.zaalime.rag.search;

import com.zaalime.rag.ingest.Chunk;

/** A retrieved chunk with its fused score and where each retriever ranked it (null = not found by it). */
public record ScoredChunk(Chunk chunk, double score, Integer vectorRank, Integer keywordRank) {
}
