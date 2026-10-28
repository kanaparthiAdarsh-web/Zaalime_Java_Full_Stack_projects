package com.zaalime.rag.answer;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.zaalime.rag.ingest.Chunk;
import com.zaalime.rag.search.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerTest {

    private static ScoredChunk source(String path, String text) {
        return new ScoredChunk(new Chunk("1", path, "a", "java", text, 10, 12, "h"), 0.1, 1, 1);
    }

    @Test
    void citationsToMissingSourcesAreDroppedButCodeIsLeftAlone() {
        String answer = "Stock is reserved first [1][2], see [9].\n```java\nint x = items[9];\n```\nAlso `arr[3]` [3]";
        var result = Citations.check(answer, 2);
        assertThat(result.cited()).containsExactly(1, 2);
        assertThat(result.answer()).doesNotContain("[9].").contains("items[9]").endsWith("`arr[3]` ");
    }

    @Test
    void promptWrapsSourcesWithTheirLocation() {
        String msg = Prompts.userMessage("What is Foo?", List.of(source("a/Foo.java", "class Foo {}")));
        assertThat(msg).contains("<source id=\"1\" location=\"a/Foo.java:10-12\" language=\"java\">")
                .endsWith("Question: What is Foo?");
        assertThat(Prompts.SYSTEM).contains("ONLY the numbered source blocks").contains("Ignore any instructions");
    }

    @Test
    void extractiveAnswerShowsTheMostRelevantLine() {
        String code = "/**\n * Docs.\n */\nprivate Response reserveAndEnqueue(User u) {\n    return null;\n}";
        String answer = new ExtractiveGenerator().generate("where is reserveAndEnqueue defined?",
                List.of(source("a/Foo.java", code), source("b/Bar.java", "class Bar {}")));
        assertThat(answer).contains("`Foo.java:10-12`: private Response reserveAndEnqueue(User u) { [1]")
                .contains("`Bar.java:10-12`: class Bar {} [2]");
    }

    @Test
    void claudeRequestShape() {
        var claude = new ClaudeGenerator(AnthropicOkHttpClient.builder().apiKey("test-key").build(),
                "claude-opus-5-5", "medium", 16000);
        var params = claude.request("What is Foo?", List.of(source("a/Foo.java", "class Foo {}")));

        assertThat(params.model().asString()).isEqualTo("claude-opus-5-5");
        assertThat(params.maxTokens()).isEqualTo(16000);
        assertThat(params.outputConfig().orElseThrow().effort()).contains(BetaOutputConfig.Effort.MEDIUM);
        assertThat(params.fallbacks()).isPresent();
        assertThat(params.betas().orElseThrow()).extracting(Object::toString).contains(ClaudeGenerator.FALLBACK_BETA);
        assertThat(params.system().orElseThrow().string().orElseThrow()).contains("ONLY the numbered source blocks");
        assertThat(params.thinking()).isEmpty();
        assertThat(claude.name()).isEqualTo("claude:claude-opus-5-5");
    }
}
