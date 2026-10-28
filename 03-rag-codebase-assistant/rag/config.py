from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Everything is configurable through environment variables (or a .env file)."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    data_dir: Path = Path("data")
    # ingestion is only allowed below this directory
    ingest_root: Path = Path("..")

    embedding_model: str = "BAAI/bge-small-en-v1.5"

    chunk_size: int = 1500          # characters
    chunk_overlap: int = 200
    max_file_bytes: int = 1_000_000

    # LLM: "auto" picks Claude if ANTHROPIC_API_KEY is set, then Ollama if OLLAMA_URL is set,
    # otherwise falls back to returning the retrieved passages without generation.
    llm_provider: str = "auto"      # auto | claude | ollama | none
    anthropic_api_key: str | None = None
    claude_model: str = "claude-opus-5-5"
    claude_effort: str = "medium"
    max_answer_tokens: int = 16000
    ollama_url: str | None = None
    ollama_model: str = "llama3.1"

    redis_url: str | None = None
    cache_ttl_seconds: int = 3600

    rate_limit_per_minute: int = 30
    admin_token: str | None = None

    default_top_k: int = 4
    candidate_pool: int = 20        # per retriever, before fusion


@lru_cache
def get_settings() -> Settings:
    return Settings()
