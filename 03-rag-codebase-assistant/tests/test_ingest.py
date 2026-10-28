import pytest

from rag.service import IngestPathError


def test_build_output_is_not_indexed(service):
    assert "shop/target/Ignored.java" not in service.store.manifest["files"]
    assert set(service.store.manifest["files"]) == {"shop/src/OrderService.java", "shop/README.md",
                                                     "tools/rate_limiter.py"}


def test_second_ingest_skips_unchanged_files(service):
    version = service.store.version
    result = service.ingest(".")
    assert result.files_indexed == 0
    assert result.files_unchanged == 3
    assert service.store.version == version  # nothing changed, cached answers stay valid


def test_changed_file_is_reindexed_and_old_chunks_removed(service, repo):
    (repo / "tools" / "rate_limiter.py").write_text("def leaky_bucket():\n    return 'drips'\n")
    version = service.store.version

    result = service.ingest("tools")

    assert result.files_indexed == 1
    assert service.store.version == version + 1
    texts = [c.text for c in service.store.all_chunks() if c.path == "tools/rate_limiter.py"]
    assert texts == ["def leaky_bucket():\n    return 'drips'"]
    assert service.retriever.search("leaky_bucket", 1, mode="keyword")[0].chunk.path == "tools/rate_limiter.py"


def test_deleted_file_is_removed_from_the_index(service, repo):
    (repo / "shop" / "README.md").unlink()
    result = service.ingest("shop")
    assert result.files_removed == 1
    assert all(c.path != "shop/README.md" for c in service.store.all_chunks())
    # files outside the ingested folder are untouched
    assert "tools/rate_limiter.py" in service.store.manifest["files"]


@pytest.mark.parametrize("path", ["..", "../..", "/etc", "shop/../../outside"])
def test_paths_outside_the_root_are_rejected(service, path):
    with pytest.raises(IngestPathError):
        service.ingest(path)


def test_index_survives_restart(settings, service):
    from rag.embeddings import HashingEmbedder
    from rag.service import RagService

    reopened = RagService(settings, embedder=HashingEmbedder())
    assert reopened.store.count() == service.store.count()
    assert len(reopened.keywords) == service.store.count()  # keyword index rebuilt from the store
