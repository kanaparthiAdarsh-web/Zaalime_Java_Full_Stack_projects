"""Hybrid retrieval: semantic (vectors) + lexical (BM25), merged with Reciprocal Rank Fusion."""

from .embeddings import Embedder
from .keyword import KeywordIndex, has_code_identifier
from .models import Chunk, ScoredChunk, SearchMode
from .store import VectorStore

RRF_K = 60  # standard constant from the RRF paper; dampens the weight of the very top ranks

# How much the keyword list counts relative to the vector list. Measured on eval/questions.jsonl:
# for plain-English questions BM25 mostly adds noise, for questions naming an identifier it is
# the most precise signal there is.
KEYWORD_WEIGHT_PROSE = 0.5
KEYWORD_WEIGHT_IDENTIFIER = 1.5


def reciprocal_rank_fusion(rankings: list[list[Chunk]], weights: list[float] | None = None,
                           k: int = RRF_K) -> list[tuple[Chunk, float, list[int | None]]]:
    """Each ranking is a list of chunks, best first. Returns (chunk, fused score, rank in each list)."""
    weights = weights or [1.0] * len(rankings)
    scores: dict[str, float] = {}
    chunks: dict[str, Chunk] = {}
    ranks: dict[str, list[int | None]] = {}
    for list_no, ranking in enumerate(rankings):
        for rank, chunk in enumerate(ranking, start=1):
            scores[chunk.id] = scores.get(chunk.id, 0.0) + weights[list_no] / (k + rank)
            chunks[chunk.id] = chunk
            ranks.setdefault(chunk.id, [None] * len(rankings))[list_no] = rank
    ordered = sorted(scores, key=scores.get, reverse=True)
    return [(chunks[cid], scores[cid], ranks[cid]) for cid in ordered]


class HybridRetriever:

    def __init__(self, store: VectorStore, keywords: KeywordIndex, embedder: Embedder, candidate_pool: int):
        self._store = store
        self._keywords = keywords
        self._embedder = embedder
        self._pool = candidate_pool

    def search(self, query: str, top_k: int, mode: SearchMode = "hybrid",
               languages: list[str] | None = None, project: str | None = None) -> list[ScoredChunk]:
        if self._store.count() == 0:
            return []

        vector_hits: list[Chunk] = []
        keyword_hits: list[Chunk] = []
        if mode in ("hybrid", "vector"):
            embedding = self._embedder.embed_query(query)
            vector_hits = [c for c, _ in self._store.query(embedding, self._pool, languages, project)]
        if mode in ("hybrid", "keyword"):
            keyword_hits = [c for c, _ in self._keywords.search(query, self._pool, languages, project)]

        keyword_weight = KEYWORD_WEIGHT_IDENTIFIER if has_code_identifier(query) else KEYWORD_WEIGHT_PROSE
        fused = reciprocal_rank_fusion([vector_hits, keyword_hits], [1.0, keyword_weight])
        results = [ScoredChunk(chunk=c, score=round(s, 5), vector_rank=r[0], keyword_rank=r[1])
                   for c, s, r in fused]
        return _dedupe_overlaps(results)[:top_k]


def _dedupe_overlaps(results: list[ScoredChunk]) -> list[ScoredChunk]:
    """Neighbouring chunks overlap by design; don't spend two of the k slots on the same lines."""
    kept: list[ScoredChunk] = []
    for r in results:
        c = r.chunk
        if any(k.chunk.path == c.path and k.chunk.language != "pdf"
               and _overlap(k.chunk, c) > 0.5 * (c.end_line - c.start_line + 1) for k in kept):
            continue
        kept.append(r)
    return kept


def _overlap(a: Chunk, b: Chunk) -> int:
    return max(0, min(a.end_line, b.end_line) - max(a.start_line, b.start_line) + 1)
