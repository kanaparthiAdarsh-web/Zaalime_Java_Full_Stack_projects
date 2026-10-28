from rag.models import Chunk
from rag.retriever import reciprocal_rank_fusion


def _chunk(cid: str) -> Chunk:
    return Chunk(id=cid, path=f"{cid}.java", project="p", language="java", text=cid, start_line=1,
                 end_line=1, file_hash="h")


def test_rrf_rewards_documents_found_by_both_retrievers():
    a, b, c = _chunk("a"), _chunk("b"), _chunk("c")
    fused = reciprocal_rank_fusion([[a, b], [c, b]])
    assert fused[0][0].id == "b"              # rank 2 in both beats rank 1 in one
    assert fused[0][2] == [2, 2]
    assert {f[0].id for f in fused} == {"a", "b", "c"}


def test_rrf_weights_shift_the_order():
    a, c = _chunk("a"), _chunk("c")
    assert reciprocal_rank_fusion([[a], [c]], [1.0, 2.0])[0][0].id == "c"
    assert reciprocal_rank_fusion([[a], [c]], [2.0, 1.0])[0][0].id == "a"


def test_exact_identifier_is_found(service):
    results = service.retriever.search("where is placeOrder defined", 3)
    assert results[0].chunk.path == "shop/src/OrderService.java"


def test_language_filter(service):
    results = service.retriever.search("requests per second", 5, languages=["python"])
    assert results and all(r.chunk.language == "python" for r in results)
    assert results[0].chunk.path == "tools/rate_limiter.py"


def test_project_filter(service):
    results = service.retriever.search("order", 5, project="tools")
    assert all(r.chunk.project == "tools" for r in results)


def test_keyword_only_and_vector_only_modes(service):
    assert service.retriever.search("TokenBucket", 2, mode="keyword")[0].chunk.path == "tools/rate_limiter.py"
    assert service.retriever.search("docker compose dashboard", 2, mode="vector")[0].chunk.path == "shop/README.md"
