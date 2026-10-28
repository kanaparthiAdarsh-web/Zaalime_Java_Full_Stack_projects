"""Walks a directory and yields the files worth indexing, with their text."""

import hashlib
import logging
from dataclasses import dataclass
from pathlib import Path

from pypdf import PdfReader

log = logging.getLogger(__name__)

LANGUAGES = {
    ".java": "java",
    ".py": "python",
    ".md": "markdown",
    ".markdown": "markdown",
    ".pdf": "pdf",
    ".js": "js",
    ".ts": "ts",
    ".sql": "sql",
    ".yml": "yaml",
    ".yaml": "yaml",
    ".txt": "text",
    ".lua": "lua",
    ".kt": "kotlin",
    ".go": "go",
    ".sh": "shell",
    ".xml": "xml",
    ".html": "html",
    ".css": "css",
}

SKIP_DIRS = {
    ".git", ".hg", ".svn", ".idea", ".vscode", "node_modules", "target", "build", "dist",
    "bin", "out", "__pycache__", ".venv", "venv", ".mypy_cache", ".pytest_cache",
}


@dataclass
class SourceFile:
    rel_path: str
    language: str
    file_hash: str
    # list of (text, first line / page number)
    sections: list[tuple[str, int]]


def iter_files(root: Path, target: Path, max_bytes: int):
    """Yields (absolute path, relative path) for every supported file under target."""
    paths = [target] if target.is_file() else sorted(target.rglob("*"))
    for path in paths:
        if not path.is_file() or path.suffix.lower() not in LANGUAGES:
            continue
        rel = path.relative_to(root)
        if any(part in SKIP_DIRS or part.startswith(".") for part in rel.parts[:-1]):
            continue
        if path.stat().st_size > max_bytes:
            log.info("Skipping %s: larger than %d bytes", rel, max_bytes)
            continue
        yield path, rel.as_posix()


def file_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path: Path, rel_path: str) -> SourceFile | None:
    language = LANGUAGES[path.suffix.lower()]
    digest = file_hash(path)
    if language == "pdf":
        sections = _read_pdf(path)
    else:
        text = _read_text(path)
        sections = [(text, 1)] if text is not None else []
    if not sections or not any(s.strip() for s, _ in sections):
        return None
    return SourceFile(rel_path, language, digest, sections)


def _read_text(path: Path) -> str | None:
    raw = path.read_bytes()
    if b"\x00" in raw[:4096]:
        return None  # binary file with a text-ish extension
    text = raw.decode("utf-8", errors="replace")
    # normalise line endings and strip trailing whitespace noise, keep line numbers intact
    return "\n".join(line.rstrip() for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"))


def _read_pdf(path: Path) -> list[tuple[str, int]]:
    try:
        reader = PdfReader(path)
    except Exception as e:  # corrupt or encrypted PDFs should not stop a whole ingest
        log.warning("Could not read PDF %s: %s", path, e)
        return []
    pages = []
    for number, page in enumerate(reader.pages, start=1):
        text = (page.extract_text() or "").strip()
        if text:
            pages.append((" ".join(text.split()), number))
    return pages
