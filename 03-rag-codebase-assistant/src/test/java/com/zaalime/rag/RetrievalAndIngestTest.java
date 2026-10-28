package com.zaalime.rag;

import com.zaalime.rag.search.HybridRetriever;
import com.zaalime.rag.search.HybridRetriever.Mode;
import com.zaalime.rag.service.IngestService.PathNotAllowedException;
import com.zaalime.rag.store.ChunkStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalAndIngestTest extends IntegrationTest {

    @Autowired
    HybridRetriever retriever;

    @Autowired
    ChunkStore store;

    private String top(String query, Mode mode, List<String> languages, String project) {
        return retriever.search(query, 4, mode, languages, project).getFirst().chunk().path();
    }

    @Test
    void buildOutputIsNotIndexed() {
        assertThat(store.indexedFiles().keySet())
                .containsExactlyInAnyOrder("shop/src/OrderService.java", "shop/README.md", "tools/rate_limiter.py");
    }

    @Test
    void exactIdentifierIsFound() {
        assertThat(top("where is placeOrder defined", Mode.HYBRID, null, null)).isEqualTo("shop/src/OrderService.java");
        assertThat(top("TokenBucket", Mode.KEYWORD, null, null)).isEqualTo("tools/rate_limiter.py");
        assertThat(top("docker compose dashboard", Mode.VECTOR, null, null)).isEqualTo("shop/README.md");
    }

    @Test
    void filtersApplyToBothRetrievers() {
        var python = retriever.search("requests per second", 5, Mode.HYBRID, List.of("python"), null);
        assertThat(python).isNotEmpty().allSatisfy(r -> assertThat(r.chunk().language()).isEqualTo("python"));
        var tools = retriever.search("order", 5, Mode.HYBRID, null, "tools");
        assertThat(tools).allSatisfy(r -> assertThat(r.chunk().project()).isEqualTo("tools"));
    }

    @Test
    void definitionBeatsMentions() throws IOException {
        for (int i = 0; i < 3; i++) {
            Files.writeString(repo().resolve("tools/notes_" + i + ".md"),
                    "# Notes " + i + "\n\nplaceOrder is called from the controller. placeOrder placeOrder placeOrder.\n");
        }
        ingest.ingest(".");
        assertThat(top("where is placeOrder defined?", Mode.HYBRID, null, null)).isEqualTo("shop/src/OrderService.java");
    }

    @Test
    void secondIngestSkipsUnchangedFiles() {
        long version = store.version();
        var result = ingest.ingest(".");
        assertThat(result.filesIndexed()).isZero();
        assertThat(result.filesUnchanged()).isEqualTo(3);
        assertThat(store.version()).isEqualTo(version); // cached answers stay valid
    }

    @Test
    void changedFileIsReindexedAndOldChunksRemoved() throws IOException {
        Files.writeString(repo().resolve("tools/rate_limiter.py"), "def leaky_bucket():\n    return 'drips'\n");
        long version = store.version();

        var result = ingest.ingest("tools");

        assertThat(result.filesIndexed()).isEqualTo(1);
        assertThat(store.version()).isEqualTo(version + 1);
        assertThat(store.all()).filteredOn(c -> c.path().equals("tools/rate_limiter.py"))
                .extracting(c -> c.text()).containsExactly("def leaky_bucket():\n    return 'drips'");
        assertThat(top("leaky_bucket", Mode.KEYWORD, null, null)).isEqualTo("tools/rate_limiter.py");
    }

    @Test
    void deletedFileIsRemovedFromTheIndex() throws IOException {
        Files.delete(repo().resolve("shop/README.md"));
        var result = ingest.ingest("shop");
        assertThat(result.filesRemoved()).isEqualTo(1);
        assertThat(store.all()).noneMatch(c -> c.path().equals("shop/README.md"));
        assertThat(store.indexedFiles()).containsKey("tools/rate_limiter.py"); // outside the ingested folder
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../..", "/etc", "shop/../../outside"})
    void pathsOutsideTheRootAreRejected(String path) {
        assertThatThrownBy(() -> ingest.ingest(path)).isInstanceOf(PathNotAllowedException.class);
    }
}
