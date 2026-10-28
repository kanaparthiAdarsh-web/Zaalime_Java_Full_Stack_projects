import pytest
from fastapi.testclient import TestClient

from rag.api import create_app
from rag.embeddings import HashingEmbedder
from rag.generator import GenerationError
from rag.service import RagService


class FakeLLM:
    name = "fake-llm"

    def __init__(self, answer="placeOrder reserves stock first [1]. See also [7]."):
        self.answer = answer
        self.calls = 0

    def generate(self, question, sources):
        self.calls += 1
        return self.answer


class BrokenLLM:
    name = "broken-llm"

    def generate(self, question, sources):
        raise GenerationError("Claude API error 529")


@pytest.fixture
def make_client(settings):
    def _make(generator=None, **overrides):
        s = settings.model_copy(update=overrides)
        svc = RagService(s, embedder=HashingEmbedder(), generator=generator or FakeLLM())
        svc.ingest(".")
        return TestClient(create_app(service=svc, settings=s)), svc
    return _make


def test_ask_returns_answer_with_checked_citations(make_client):
    with make_client()[0] as client:
        r = client.post("/ask", json={"question": "where is placeOrder defined?"})
    assert r.status_code == 200
    body = r.json()
    assert body["generator"] == "fake-llm"
    # [1] is real and kept, [7] does not exist and is stripped
    assert "[1]" in body["answer"] and "[7]" not in body["answer"]
    assert body["sources"][0]["path"] == "shop/src/OrderService.java"
    assert body["sources"][0]["cited"] is True
    assert body["sources"][0]["location"].startswith("shop/src/OrderService.java:")


def test_repeated_question_is_served_from_cache(make_client):
    llm = FakeLLM()
    client, _ = make_client(generator=llm)
    with client:
        first = client.post("/ask", json={"question": "How are orders placed?"}).json()
        second = client.post("/ask", json={"question": "  how are ORDERS placed? "}).json()
    assert first["cached"] is False
    assert second["cached"] is True
    assert second["answer"] == first["answer"]
    assert llm.calls == 1


def test_reingest_invalidates_the_cache(make_client, repo):
    llm = FakeLLM()
    client, svc = make_client(generator=llm)
    with client:
        client.post("/ask", json={"question": "How are orders placed?"})
        (repo / "shop" / "src" / "OrderService.java").write_text("public class OrderService { void placeOrder() {} }")
        svc.ingest(".")
        r = client.post("/ask", json={"question": "How are orders placed?"}).json()
    assert r["cached"] is False
    assert llm.calls == 2


def test_llm_failure_degrades_to_extractive_answer(make_client):
    with make_client(generator=BrokenLLM())[0] as client:
        r = client.post("/ask", json={"question": "How are orders placed?"}).json()
    assert r["generator"] == "extractive"
    assert "529" in r["warning"]
    assert r["sources"]


def test_filters_are_applied(make_client):
    with make_client()[0] as client:
        r = client.post("/ask", json={"question": "requests per second", "languages": ["python"]}).json()
    assert {s["language"] for s in r["sources"]} == {"python"}


def test_no_matching_sources_does_not_call_the_llm(make_client):
    llm = FakeLLM()
    client, _ = make_client(generator=llm)
    with client:
        r = client.post("/ask", json={"question": "anything at all", "languages": ["pdf"]}).json()
    assert r["sources"] == []
    assert "couldn't find" in r["answer"]
    assert llm.calls == 0


def test_rate_limit(make_client):
    client, _ = make_client(rate_limit_per_minute=2)
    with client:
        codes = [client.post("/ask", json={"question": f"question number {i}"}).status_code for i in range(3)]
        last = client.post("/ask", json={"question": "one more"})
    assert codes == [200, 200, 429]
    assert int(last.headers["Retry-After"]) >= 1


def test_ingest_endpoint_requires_admin_token(make_client):
    with make_client()[0] as client:
        assert client.post("/ingest", json={"path": "."}).status_code == 401
        assert client.post("/ingest", json={"path": "."}, headers={"X-Admin-Token": "wrong"}).status_code == 401
        ok = client.post("/ingest", json={"path": "."}, headers={"X-Admin-Token": "secret-token"})
        bad = client.post("/ingest", json={"path": "../.."}, headers={"X-Admin-Token": "secret-token"})
    assert ok.status_code == 200 and ok.json()["files_unchanged"] == 3
    assert bad.status_code == 400


def test_ingest_endpoint_disabled_without_token(make_client):
    with make_client(admin_token=None)[0] as client:
        assert client.post("/ingest", json={"path": "."}).status_code == 403


def test_validation_and_ui(make_client):
    with make_client()[0] as client:
        assert client.post("/ask", json={"question": "x"}).status_code == 422
        assert client.post("/ask", json={"question": "valid question", "top_k": 50}).status_code == 422
        assert client.get("/").status_code == 200
        stats = client.get("/stats").json()
    assert stats["files"] == 3 and stats["generator"] == "fake-llm"
