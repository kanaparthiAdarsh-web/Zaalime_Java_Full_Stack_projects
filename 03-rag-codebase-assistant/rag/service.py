import logging
import threading
import time
from pathlib import Path

from .cache import AnswerCache, cache_key
from .chunker import Chunker, embedding_text
from .config import Settings
from .embeddings import Embedder, FastEmbedEmbedder
from .generator import (NO_CONTEXT_ANSWER, ClaudeGenerator, ExtractiveGenerator, GenerationError, Generator,
                        OllamaGenerator, check_citations)
from .keyword import KeywordIndex
from .loader import iter_files, load
from .models import AskRequest, AskResponse, IngestResult, SourceOut
from .retriever import HybridRetriever
from .store import VectorStore

log = logging.getLogger(__name__)


class IngestPathError(ValueError):
    pass


def build_generator(settings: Settings) -> Generator:
    provider = settings.llm_provider.lower()
    if provider == "auto":
        provider = "claude" if settings.anthropic_api_key else "ollama" if settings.ollama_url else "none"
    if provider == "claude":
        return ClaudeGenerator(settings.claude_model, settings.claude_effort, settings.max_answer_tokens,
                               settings.anthropic_api_key)
    if provider == "ollama":
        if not settings.ollama_url:
            raise ValueError("LLM_PROVIDER=ollama needs OLLAMA_URL")
        return OllamaGenerator(settings.ollama_url, settings.ollama_model)
    return ExtractiveGenerator()


class RagService:

    def __init__(self, settings: Settings, embedder: Embedder | None = None, generator: Generator | None = None):
        self.settings = settings
        self.embedder = embedder or FastEmbedEmbedder(
            settings.embedding_model, cache_dir=str(settings.model_cache_dir or settings.data_dir / "models"))
        self.store = VectorStore(settings.data_dir, self.embedder.name)
        self.keywords = KeywordIndex()
        self.keywords.rebuild(self.store.all_chunks())
        self.retriever = HybridRetriever(self.store, self.keywords, self.embedder, settings.candidate_pool)
        self.generator = generator or build_generator(settings)
        self.fallback_generator = ExtractiveGenerator()
        self.cache = AnswerCache(settings.redis_url, settings.cache_ttl_seconds)
        self.chunker = Chunker(settings.chunk_size, settings.chunk_overlap)
        self._ingest_lock = threading.Lock()
        log.info("RAG ready: %d chunks indexed, generator=%s, cache=%s",
                 self.store.count(), self.generator.name, self.cache.backend)

    # ------------------------------------------------------------------ ingest

    def resolve(self, path: str) -> Path:
        root = self.settings.ingest_root.resolve()
        target = (root / path).resolve()
        if target != root and root not in target.parents:
            raise IngestPathError(f"'{path}' is outside the allowed ingest root")
        if not target.exists():
            raise IngestPathError(f"'{path}' does not exist")
        return target

    def ingest(self, path: str = ".") -> IngestResult:
        started = time.perf_counter()
        target = self.resolve(path)
        root = self.settings.ingest_root.resolve()
        with self._ingest_lock:
            files: dict = self.store.manifest["files"]
            seen: set[str] = set()
            indexed = unchanged = skipped = chunks_added = 0

            for abs_path, rel in iter_files(root, target, self.settings.max_file_bytes):
                seen.add(rel)
                source = load(abs_path, rel)
                if source is None:
                    skipped += 1
                    if rel in files:  # used to be indexable, now empty or unreadable
                        self.store.delete_file(rel)
                        del files[rel]
                        indexed += 1
                    continue
                if files.get(rel) == source.file_hash:
                    unchanged += 1
                    continue
                chunks = self.chunker.chunk(source)
                embeddings = self.embedder.embed_documents([embedding_text(c) for c in chunks])
                self.store.delete_file(rel)
                self.store.upsert(chunks, embeddings)
                files[rel] = source.file_hash
                indexed += 1
                chunks_added += len(chunks)

            # files that were indexed under this folder before but are gone now
            prefix = target.relative_to(root).as_posix()
            in_scope = (lambda p: True) if prefix == "." else (lambda p: p == prefix or p.startswith(prefix + "/"))
            removed = [p for p in files if in_scope(p) and p not in seen]
            for p in removed:
                self.store.delete_file(p)
                del files[p]

            if indexed or removed:
                self.store.manifest["version"] += 1  # invalidates cached answers
                self.store.save_manifest()
                self.keywords.rebuild(self.store.all_chunks())

        return IngestResult(files_seen=len(seen), files_indexed=indexed, files_unchanged=unchanged,
                            files_removed=len(removed), files_skipped=skipped, chunks_added=chunks_added,
                            total_chunks=self.store.count(), seconds=round(time.perf_counter() - started, 2))

    # ------------------------------------------------------------------ ask

    def ask(self, req: AskRequest) -> AskResponse:
        t0 = time.perf_counter()
        top_k = req.top_k or self.settings.default_top_k
        languages = [l.lower() for l in req.languages] if req.languages else None
        key = cache_key(req.question, top_k=top_k, languages=sorted(languages or []), project=req.project,
                        mode=req.mode, index=self.store.version, generator=self.generator.name)

        cached = self.cache.get(key)
        if cached:
            response = AskResponse(**cached)
            response.cached = True
            response.timings_ms = {"total": _ms(t0)}
            return response

        results = self.retriever.search(req.question, top_k, req.mode, languages, req.project)
        t_retrieval = _ms(t0)
        if not results:
            return AskResponse(answer=NO_CONTEXT_ANSWER, sources=[], generator="none",
                               timings_ms={"retrieval": t_retrieval, "total": _ms(t0)})

        warning = None
        generator = self.generator
        t1 = time.perf_counter()
        try:
            answer = generator.generate(req.question, results)
        except GenerationError as e:
            # degrade instead of failing: the retrieved passages are still a useful answer
            log.warning("Generation failed (%s), falling back to extractive answer", e)
            warning = f"{e}. Showing the matching passages instead."
            generator = self.fallback_generator
            answer = generator.generate(req.question, results)
        t_generation = _ms(t1)

        answer, cited = check_citations(answer, len(results))
        sources = [
            SourceOut(id=i, path=r.chunk.path, location=r.chunk.location, language=r.chunk.language,
                      start_line=r.chunk.start_line, end_line=r.chunk.end_line, score=r.score,
                      snippet=r.chunk.text, cited=i in cited)
            for i, r in enumerate(results, start=1)
        ]
        response = AskResponse(answer=answer, sources=sources, generator=generator.name, warning=warning,
                               timings_ms={"retrieval": t_retrieval, "generation": t_generation, "total": _ms(t0)})
        if warning is None:
            self.cache.put(key, response.model_dump(exclude={"cached", "timings_ms"}))
        return response


def _ms(since: float) -> float:
    return round((time.perf_counter() - since) * 1000, 1)
