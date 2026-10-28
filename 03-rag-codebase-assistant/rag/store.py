"""Persistent vector store (ChromaDB) plus a small manifest of what has been indexed."""

import json
import threading
from pathlib import Path

import chromadb
from chromadb.config import Settings as ChromaSettings

from .models import Chunk

COLLECTION = "codebase"


class VectorStore:

    def __init__(self, data_dir: Path, embedding_model: str):
        data_dir.mkdir(parents=True, exist_ok=True)
        self._client = chromadb.PersistentClient(
            path=str(data_dir / "chroma"), settings=ChromaSettings(anonymized_telemetry=False))
        self._collection = self._client.get_or_create_collection(
            COLLECTION, metadata={"hnsw:space": "cosine", "embedding_model": embedding_model})
        stored_model = (self._collection.metadata or {}).get("embedding_model")
        if stored_model and stored_model != embedding_model:
            raise RuntimeError(
                f"Index was built with '{stored_model}' but EMBEDDING_MODEL is '{embedding_model}'. "
                "Delete the data directory and re-ingest.")
        self._manifest_path = data_dir / "manifest.json"
        self._lock = threading.Lock()
        self.manifest: dict = self._load_manifest()

    # --- manifest: path -> file hash, plus an index version used to invalidate caches ---

    def _load_manifest(self) -> dict:
        if self._manifest_path.exists():
            return json.loads(self._manifest_path.read_text())
        return {"version": 0, "files": {}}

    def save_manifest(self) -> None:
        tmp = self._manifest_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.manifest, indent=2, sort_keys=True))
        tmp.replace(self._manifest_path)  # atomic on POSIX

    @property
    def version(self) -> int:
        return self.manifest["version"]

    # --- chunks ---

    def upsert(self, chunks: list[Chunk], embeddings: list[list[float]]) -> None:
        if not chunks:
            return
        with self._lock:
            for start in range(0, len(chunks), 500):
                batch = chunks[start:start + 500]
                self._collection.upsert(
                    ids=[c.id for c in batch],
                    embeddings=embeddings[start:start + 500],
                    documents=[c.text for c in batch],
                    metadatas=[c.metadata() for c in batch],
                )

    def delete_file(self, path: str) -> None:
        with self._lock:
            self._collection.delete(where={"path": path})

    def count(self) -> int:
        return self._collection.count()

    def query(self, embedding: list[float], limit: int, languages: list[str] | None = None,
              project: str | None = None) -> list[tuple[Chunk, float]]:
        where = _where(languages, project)
        res = self._collection.query(
            query_embeddings=[embedding], n_results=limit, where=where,
            include=["documents", "metadatas", "distances"])
        out = []
        for cid, doc, meta, dist in zip(res["ids"][0], res["documents"][0], res["metadatas"][0], res["distances"][0]):
            out.append((_to_chunk(cid, doc, meta), 1.0 - float(dist)))  # cosine similarity
        return out

    def all_chunks(self) -> list[Chunk]:
        res = self._collection.get(include=["documents", "metadatas"])
        return [_to_chunk(cid, doc, meta) for cid, doc, meta in zip(res["ids"], res["documents"], res["metadatas"])]

    def stats(self) -> dict:
        res = self._collection.get(include=["metadatas"])
        by_language: dict[str, int] = {}
        projects: dict[str, int] = {}
        for meta in res["metadatas"]:
            by_language[meta["language"]] = by_language.get(meta["language"], 0) + 1
            projects[meta["project"]] = projects.get(meta["project"], 0) + 1
        return {"chunks": len(res["ids"]), "files": len(self.manifest["files"]), "index_version": self.version,
                "by_language": by_language, "projects": projects}


def _where(languages: list[str] | None, project: str | None) -> dict | None:
    clauses = []
    if languages:
        clauses.append({"language": {"$in": languages}})
    if project:
        clauses.append({"project": project})
    if not clauses:
        return None
    return clauses[0] if len(clauses) == 1 else {"$and": clauses}


def _to_chunk(cid: str, doc: str, meta: dict) -> Chunk:
    return Chunk(id=cid, path=meta["path"], project=meta["project"], language=meta["language"], text=doc,
                 start_line=int(meta["start_line"]), end_line=int(meta["end_line"]), file_hash=meta["file_hash"])
