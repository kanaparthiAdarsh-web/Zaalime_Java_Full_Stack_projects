from dataclasses import dataclass, field
from typing import Literal

from pydantic import BaseModel, Field


@dataclass
class Chunk:
    id: str
    path: str           # relative to the ingest root, always with forward slashes
    project: str        # first path segment, used for filtering
    language: str       # java, python, markdown, pdf, ...
    text: str           # raw text shown to the user and the LLM
    start_line: int     # 1-based; for PDFs this is the page number
    end_line: int
    file_hash: str
    extra: dict = field(default_factory=dict)

    @property
    def location(self) -> str:
        if self.language == "pdf":
            return f"{self.path} (page {self.start_line})"
        return f"{self.path}:{self.start_line}-{self.end_line}"

    def metadata(self) -> dict:
        return {
            "path": self.path,
            "project": self.project,
            "language": self.language,
            "start_line": self.start_line,
            "end_line": self.end_line,
            "file_hash": self.file_hash,
        }


@dataclass
class ScoredChunk:
    chunk: Chunk
    score: float
    vector_rank: int | None = None
    keyword_rank: int | None = None


SearchMode = Literal["hybrid", "vector", "keyword"]


class AskRequest(BaseModel):
    question: str = Field(min_length=3, max_length=2000)
    top_k: int | None = Field(default=None, ge=1, le=12)
    languages: list[str] | None = Field(default=None, description="e.g. ['java', 'markdown']")
    project: str | None = Field(default=None, description="top-level folder, e.g. '02-distributed-order-inventory-system'")
    mode: SearchMode = "hybrid"


class SourceOut(BaseModel):
    id: int
    path: str
    location: str
    language: str
    start_line: int
    end_line: int
    score: float
    snippet: str
    cited: bool


class AskResponse(BaseModel):
    answer: str
    sources: list[SourceOut]
    generator: str
    cached: bool = False
    warning: str | None = None
    timings_ms: dict[str, float] = {}


class IngestRequest(BaseModel):
    path: str = Field(description="directory or file, relative to INGEST_ROOT")


class IngestResult(BaseModel):
    files_seen: int
    files_indexed: int
    files_unchanged: int
    files_removed: int
    files_skipped: int
    chunks_added: int
    total_chunks: int
    seconds: float
