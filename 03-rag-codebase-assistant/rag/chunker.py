"""Splits files into overlapping chunks along natural boundaries (classes, functions, headings)."""

import hashlib

from langchain_text_splitters import Language, RecursiveCharacterTextSplitter

from .loader import SourceFile
from .models import Chunk

_SPLITTER_LANGUAGE = {
    "java": Language.JAVA,
    "python": Language.PYTHON,
    "markdown": Language.MARKDOWN,
    "js": Language.JS,
    "ts": Language.TS,
    "lua": Language.LUA,
    "kotlin": Language.KOTLIN,
    "go": Language.GO,
    "html": Language.HTML,
}


class Chunker:

    def __init__(self, chunk_size: int, chunk_overlap: int):
        self._size = chunk_size
        self._overlap = chunk_overlap
        self._splitters: dict[str, RecursiveCharacterTextSplitter] = {}

    def _splitter(self, language: str) -> RecursiveCharacterTextSplitter:
        if language not in self._splitters:
            lang = _SPLITTER_LANGUAGE.get(language)
            if lang is not None:
                splitter = RecursiveCharacterTextSplitter.from_language(
                    lang, chunk_size=self._size, chunk_overlap=self._overlap, add_start_index=True,
                    strip_whitespace=False)
            else:
                splitter = RecursiveCharacterTextSplitter(
                    chunk_size=self._size, chunk_overlap=self._overlap, add_start_index=True,
                    strip_whitespace=False)
            self._splitters[language] = splitter
        return self._splitters[language]

    def chunk(self, source: SourceFile) -> list[Chunk]:
        project = source.rel_path.split("/", 1)[0] if "/" in source.rel_path else "."
        chunks: list[Chunk] = []
        for text, first_line in source.sections:
            for doc in self._splitter(source.language).create_documents([text]):
                raw = doc.page_content
                if not raw.strip():
                    continue
                # keep indentation (it matters in code) but drop whole blank lines at both ends
                lead = len(raw) - len(raw.lstrip())
                lead = raw.rfind("\n", 0, lead) + 1  # back to the start of the first non-blank line
                body = raw[lead:].rstrip()
                start = doc.metadata["start_index"] + lead
                if source.language == "pdf":
                    start_line = end_line = first_line  # page number
                else:
                    start_line = first_line + text.count("\n", 0, start)
                    end_line = start_line + body.count("\n")
                chunk_id = hashlib.sha1(
                    f"{source.rel_path}|{start_line}|{len(chunks)}|{source.file_hash}".encode()).hexdigest()
                chunks.append(Chunk(
                    id=chunk_id, path=source.rel_path, project=project, language=source.language,
                    text=body, start_line=start_line, end_line=end_line, file_hash=source.file_hash))
        return chunks


def embedding_text(chunk: Chunk) -> str:
    """What actually gets embedded: the chunk plus a short header saying where it comes from.

    Code chunks often lack the context that makes them findable ("which class is this
    method in?"), and the file path carries a lot of that meaning.
    """
    return f"File: {chunk.path} ({chunk.language})\n\n{chunk.text}"
