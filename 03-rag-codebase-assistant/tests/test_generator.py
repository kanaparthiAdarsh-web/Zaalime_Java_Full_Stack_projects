from types import SimpleNamespace

import pytest

from rag.generator import ClaudeGenerator, ExtractiveGenerator, GenerationError, build_user_message, check_citations
from rag.models import Chunk, ScoredChunk


def _source(path="a/Foo.java", text="class Foo {}") -> ScoredChunk:
    return ScoredChunk(Chunk(id="1", path=path, project="a", language="java", text=text, start_line=10,
                             end_line=12, file_hash="h"), score=0.1)


def test_citation_check_keeps_valid_drops_invalid_and_ignores_code():
    answer = "Stock is reserved first [1][2], see [9].\n```java\nint x = items[9];\n```\nAlso `arr[3]` [3]"
    cleaned, cited = check_citations(answer, source_count=2)
    assert cited == {1, 2}
    assert "[9]." not in cleaned and "items[9]" in cleaned and "`arr[3]`" in cleaned
    assert cleaned.endswith("`arr[3]` ")


def test_user_message_wraps_sources_with_locations():
    msg = build_user_message("What is Foo?", [_source()])
    assert '<source id="1" location="a/Foo.java:10-12" language="java">' in msg
    assert msg.endswith("Question: What is Foo?")


def test_extractive_answer_cites_every_source():
    text = ExtractiveGenerator().generate("q", [_source(), _source("b/Bar.java", "class Bar {}")])
    assert "[1]" in text and "[2]" in text and "`Bar.java:10-12`" in text


class _FakeMessages:
    def __init__(self, response):
        self.response = response
        self.kwargs = None

    def create(self, **kwargs):
        self.kwargs = kwargs
        return self.response


def _claude_with(response) -> tuple[ClaudeGenerator, _FakeMessages]:
    gen = ClaudeGenerator("claude-opus-5-5", "medium", 16000, api_key="test-key")
    fake = _FakeMessages(response)
    gen._client = SimpleNamespace(beta=SimpleNamespace(messages=fake))
    return gen, fake


def test_claude_request_shape():
    response = SimpleNamespace(stop_reason="end_turn",
                               content=[SimpleNamespace(type="thinking", thinking=""),
                                        SimpleNamespace(type="text", text="Foo is a class [1].")])
    gen, fake = _claude_with(response)

    assert gen.generate("What is Foo?", [_source()]) == "Foo is a class [1]."
    assert fake.kwargs["model"] == "claude-opus-5-5"
    assert fake.kwargs["fallbacks"] == "default"
    assert fake.kwargs["betas"] == ["server-side-fallback-2026-07-01"]
    assert fake.kwargs["output_config"] == {"effort": "medium"}
    assert "ONLY the numbered source blocks" in fake.kwargs["system"]
    assert "thinking" not in fake.kwargs and "temperature" not in fake.kwargs


def test_claude_refusal_raises():
    gen, _ = _claude_with(SimpleNamespace(stop_reason="refusal", content=[]))
    with pytest.raises(GenerationError, match="declined"):
        gen.generate("q", [_source()])


def test_claude_truncated_answer_is_marked():
    gen, _ = _claude_with(SimpleNamespace(stop_reason="max_tokens", content=[SimpleNamespace(type="text", text="Partial")]))
    assert gen.generate("q", [_source()]).endswith("_(answer truncated)_")


def test_extractive_answer_shows_the_most_relevant_line():
    text = "/**\n * Docs.\n */\nprivate Response reserveAndEnqueue(User u) {\n    return null;\n}"
    answer = ExtractiveGenerator().generate("where is reserveAndEnqueue defined?", [_source(text=text)])
    assert "`Foo.java:10-12`: private Response reserveAndEnqueue(User u) {" in answer
