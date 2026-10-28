package com.zaalime.rag.search;

import com.zaalime.rag.ingest.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FusionTest {

    private static Chunk chunk(String id) {
        return new Chunk(id, id + ".java", "p", "java", id, 1, 1, "h");
    }

    @Test
    void documentFoundByBothRetrieversWins() {
        Chunk a = chunk("a"), b = chunk("b"), c = chunk("c");
        var fused = HybridRetriever.fuse(List.of(List.of(a, b), List.of(c, b)), List.of(1.0, 1.0));
        assertThat(fused.getFirst().chunk().id()).isEqualTo("b"); // rank 2 + rank 2 beats a single rank 1
        assertThat(fused.getFirst().vectorRank()).isEqualTo(2);
        assertThat(fused.getFirst().keywordRank()).isEqualTo(2);
        assertThat(fused).extracting(s -> s.chunk().id()).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void weightsShiftTheOrder() {
        Chunk a = chunk("a"), c = chunk("c");
        assertThat(HybridRetriever.fuse(List.of(List.of(a), List.of(c)), List.of(1.0, 2.0)).getFirst().chunk().id()).isEqualTo("c");
        assertThat(HybridRetriever.fuse(List.of(List.of(a), List.of(c)), List.of(2.0, 1.0)).getFirst().chunk().id()).isEqualTo("a");
    }
}
