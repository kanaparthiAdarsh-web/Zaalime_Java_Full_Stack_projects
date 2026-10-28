"""BM25 keyword index. Catches exact identifiers that embeddings tend to blur
(e.g. `reserveAndEnqueue`, `ORDER_QUEUE`, `PriceLevel`)."""

import re

from rank_bm25 import BM25Okapi

from .models import Chunk

_WORD = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|\d+")
_CAMEL = re.compile(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|\d+")


# question words and glue; without this, long prose files (READMEs) win on "how does the ..."
STOPWORDS = frozenset("""
a an and are as at be been but by can could did do does doing done for from has have how i if in into is it
its me my no not of on or our should so than that the their them then there these they this to too up us was
we were what when where which who whom why will with would you your yours about after again all also any
because before between both during each few more most other only own same some such through under until
very while does get gets got make makes made happen happens happened use used uses using work works
""".split())


def tokenize(text: str) -> list[str]:
    """Code-aware tokens: keeps whole identifiers and also their camelCase / snake_case parts,
    so both "placeOrder" and "place order" match."""
    tokens: list[str] = []
    for word in _WORD.findall(text):
        lower = word.lower()
        if lower in STOPWORDS:
            continue
        tokens.append(lower)
        parts = [p.lower() for piece in word.split("_") for p in _CAMEL.findall(piece)]
        if len(parts) > 1:
            tokens.extend(p for p in parts if p not in STOPWORDS)
    return tokens


_IDENTIFIER = re.compile(r"\b(?:[a-z]+[A-Z]\w*|[A-Z][a-z]+[A-Z]\w*|\w+_\w+|\w+\(\))")


def has_code_identifier(query: str) -> bool:
    """camelCase, PascalCase with an inner capital, snake_case or call() in the question."""
    return bool(_IDENTIFIER.search(query))


def code_identifiers(query: str) -> list[str]:
    return [m.rstrip("()") for m in _IDENTIFIER.findall(query)]


_NOT_A_DECLARATION = ("return", "new", "throw", "await", "yield", "if", "while", "else", "case")


def defines(text: str, identifier: str) -> bool:
    """Rough check that a chunk declares `identifier` rather than just mentioning it.

    Catches `def x(`, `class X`, `function x(`, `interface X`, and Java/TS style
    `public Foo x(` method declarations; skips calls such as `return x(...)` or `y = x(...)`.
    """
    ident = re.escape(identifier)
    keyword_decl = re.compile(rf"\b(?:def|class|interface|enum|record|function|struct|type)\s+{ident}\b")
    if keyword_decl.search(text):
        return True
    method_decl = re.compile(rf"^\s*((?:[\w@<>\[\],.?]+\s+)+){ident}\s*\(", re.M)
    for m in method_decl.finditer(text):
        first_word = m.group(1).split()[0]
        line = text[m.start():text.find("\n", m.start()) if "\n" in text[m.start():] else len(text)]
        if first_word not in _NOT_A_DECLARATION and "=" not in line.split(identifier)[0]:
            return True
    return False


class KeywordIndex:

    def __init__(self) -> None:
        self._chunks: list[Chunk] = []
        self._bm25: BM25Okapi | None = None

    def rebuild(self, chunks: list[Chunk]) -> None:
        self._chunks = chunks
        corpus = [tokenize(f"{c.path} {c.text}") for c in chunks]
        self._bm25 = BM25Okapi(corpus) if corpus else None

    def __len__(self) -> int:
        return len(self._chunks)

    def search(self, query: str, limit: int, languages: list[str] | None = None,
               project: str | None = None) -> list[tuple[Chunk, float]]:
        if self._bm25 is None:
            return []
        terms = tokenize(query)
        if not terms:
            return []
        scores = self._bm25.get_scores(terms)
        ranked = sorted(range(len(scores)), key=lambda i: scores[i], reverse=True)
        results = []
        for i in ranked:
            if scores[i] <= 0:
                break
            chunk = self._chunks[i]
            if languages and chunk.language not in languages:
                continue
            if project and chunk.project != project:
                continue
            results.append((chunk, float(scores[i])))
            if len(results) == limit:
                break
        return results
