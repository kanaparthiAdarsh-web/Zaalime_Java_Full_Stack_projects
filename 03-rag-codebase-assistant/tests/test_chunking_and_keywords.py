from rag.chunker import Chunker
from rag.keyword import has_code_identifier, tokenize
from rag.loader import load

from .conftest import ORDER_SERVICE


def test_chunk_line_numbers_point_at_the_real_lines(repo):
    path = repo / "shop" / "src" / "OrderService.java"
    source = load(path, "shop/src/OrderService.java")
    chunks = Chunker(chunk_size=200, chunk_overlap=40).chunk(source)

    assert len(chunks) > 1
    lines = ORDER_SERVICE.split("\n")
    for c in chunks:
        assert c.project == "shop"
        assert c.language == "java"
        # the chunk text is exactly the file content between the reported lines
        assert "\n".join(lines[c.start_line - 1:c.end_line]) == c.text


def test_chunks_cover_the_whole_file(repo):
    source = load(repo / "shop" / "src" / "OrderService.java", "shop/src/OrderService.java")
    chunks = Chunker(chunk_size=200, chunk_overlap=40).chunk(source)
    covered = set()
    for c in chunks:
        covered.update(range(c.start_line, c.end_line + 1))
    non_blank = {i for i, line in enumerate(ORDER_SERVICE.split("\n"), start=1) if line.strip()}
    assert non_blank <= covered


def test_tokenizer_splits_identifiers_and_drops_stopwords():
    tokens = tokenize("How does placeOrder call try_reserve on the HTTPServer?")
    assert "placeorder" in tokens and "place" in tokens and "order" in tokens
    assert "try_reserve" in tokens and "try" in tokens and "reserve" in tokens
    assert "httpserver" in tokens and "http" in tokens and "server" in tokens
    assert "how" not in tokens and "the" not in tokens and "does" not in tokens


def test_code_identifier_detection():
    assert has_code_identifier("where is reserveAndEnqueue defined")
    assert has_code_identifier("what does try_reserve do")
    assert has_code_identifier("explain OrderEventHandler")
    assert has_code_identifier("who calls start()")
    assert not has_code_identifier("How does checkout prevent overselling?")


def test_definition_detection():
    from rag.keyword import code_identifiers, defines

    assert code_identifiers("where is reserveAndEnqueue defined, and who calls start()?") == ["reserveAndEnqueue", "start"]
    assert defines("    private CheckoutResponse reserveAndEnqueue(AuthUser user) {", "reserveAndEnqueue")
    assert defines("public static <T> List<T> parseAll(String s) {", "parseAll")
    assert defines("def place_order(self, item):", "place_order")
    assert defines("class OrderService {", "OrderService")
    assert not defines("        return reserveAndEnqueue(user, key);", "reserveAndEnqueue")
    assert not defines("        CheckoutResponse r = reserveAndEnqueue(user);", "reserveAndEnqueue")
    assert not defines('<button>Where is reserveAndEnqueue defined?</button>', "reserveAndEnqueue")
