package com.zaalime.rag.ingest;

import com.zaalime.rag.SampleRepo;
import com.zaalime.rag.ingest.SourceFiles.Section;
import com.zaalime.rag.ingest.SourceFiles.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CodeChunkerTest {

    private static SourceFile java(String text) {
        return new SourceFile("shop/src/OrderService.java", "java", "hash", List.of(new Section(text, 1)));
    }

    @Test
    void chunkTextIsExactlyTheReportedLines() {
        List<Chunk> chunks = new CodeChunker(200, 40).chunk(java(SampleRepo.ORDER_SERVICE));

        assertThat(chunks).hasSizeGreaterThan(1);
        String[] lines = SampleRepo.ORDER_SERVICE.split("\n", -1);
        for (Chunk c : chunks) {
            assertThat(c.project()).isEqualTo("shop");
            String expected = String.join("\n", List.of(lines).subList(c.startLine() - 1, c.endLine()));
            assertThat(c.text()).isEqualTo(expected); // indentation included
        }
    }

    @Test
    void everyNonBlankLineIsCovered() {
        List<Chunk> chunks = new CodeChunker(200, 40).chunk(java(SampleRepo.ORDER_SERVICE));
        Set<Integer> covered = new HashSet<>();
        chunks.forEach(c -> {
            for (int i = c.startLine(); i <= c.endLine(); i++) {
                covered.add(i);
            }
        });
        String[] lines = SampleRepo.ORDER_SERVICE.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                assertThat(covered).as("line %d", i + 1).contains(i + 1);
            }
        }
    }

    @Test
    void prefersToCutAtDeclarations() {
        List<Chunk> chunks = new CodeChunker(300, 0).chunk(java(SampleRepo.ORDER_SERVICE));
        // the placeOrder method starts a chunk instead of being split down the middle
        assertThat(chunks).anySatisfy(c -> assertThat(c.text().strip()).startsWith("public Order placeOrder("));
    }

    @Test
    void docCommentStaysWithItsMethod() {
        String code = "class A {\n" + "    int x;\n".repeat(20)
                + "    /**\n     * Explains b.\n     */\n    @Override\n    public void b() {\n    }\n}\n";
        List<Chunk> chunks = new CodeChunker(250, 0).chunk(java(code));
        Chunk withMethod = chunks.stream().filter(c -> c.text().contains("public void b()")).findFirst().orElseThrow();
        assertThat(withMethod.text()).contains("Explains b.").contains("@Override");
    }

    @Test
    void markdownSplitsAtHeadings() {
        String md = "# Title\n\n" + "intro line\n".repeat(10) + "\n## Install\n\n" + "step\n".repeat(10);
        var file = new SourceFile("docs/guide.md", "markdown", "h", List.of(new Section(md, 1)));
        List<Chunk> chunks = new CodeChunker(150, 0).chunk(file);
        assertThat(chunks).anySatisfy(c -> assertThat(c.text()).startsWith("## Install"));
    }

    @Test
    void pdfChunksCarryThePageNumber() {
        String page = "First sentence about payments. ".repeat(30);
        var file = new SourceFile("docs/manual.pdf", "pdf", "h", List.of(new Section(page, 7)));
        List<Chunk> chunks = new CodeChunker(300, 50).chunk(file);
        assertThat(chunks).hasSizeGreaterThan(1)
                .allSatisfy(c -> assertThat(c.startLine()).isEqualTo(7))
                .allSatisfy(c -> assertThat(c.location()).isEqualTo("docs/manual.pdf (page 7)"));
    }

    @Test
    void chunkIdsChangeWhenTheFileChanges() {
        var a = new CodeChunker(200, 40).chunk(java(SampleRepo.ORDER_SERVICE)).getFirst();
        var b = new CodeChunker(200, 40).chunk(new SourceFile("shop/src/OrderService.java", "java", "other-hash",
                List.of(new Section(SampleRepo.ORDER_SERVICE, 1)))).getFirst();
        assertThat(a.id()).isNotEqualTo(b.id());
    }
}
