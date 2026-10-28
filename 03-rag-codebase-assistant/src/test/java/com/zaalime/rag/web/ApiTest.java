package com.zaalime.rag.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaalime.rag.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTest extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    private JsonNode ask(Map<String, Object> body) {
        ResponseEntity<JsonNode> r = http.postForEntity("/ask", body, JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void answerHasCheckedCitationsAndSources() {
        JsonNode r = ask(Map.of("question", "where is placeOrder defined?"));
        assertThat(r.get("generator").asText()).isEqualTo("scripted-llm");
        // [1] exists and is kept, [7] does not and is stripped
        assertThat(r.get("answer").asText()).contains("[1]").doesNotContain("[7]");
        JsonNode first = r.get("sources").get(0);
        assertThat(first.get("path").asText()).isEqualTo("shop/src/OrderService.java");
        assertThat(first.get("cited").asBoolean()).isTrue();
        assertThat(first.get("location").asText()).startsWith("shop/src/OrderService.java:");
        assertThat(first.get("start_line").asInt()).isPositive();
    }

    @Test
    void rephrasedRepeatQuestionIsServedFromCache() {
        JsonNode first = ask(Map.of("question", "How are orders placed?"));
        JsonNode second = ask(Map.of("question", "  how are ORDERS placed? "));
        assertThat(first.get("cached").asBoolean()).isFalse();
        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.get("answer")).isEqualTo(first.get("answer"));
        assertThat(llm.calls()).isEqualTo(1);
    }

    @Test
    void reingestInvalidatesTheCache() throws IOException {
        ask(Map.of("question", "How are orders placed?"));
        Files.writeString(repo().resolve("shop/src/OrderService.java"), "public class OrderService { void placeOrder() {} }");
        ingest.ingest(".");
        JsonNode again = ask(Map.of("question", "How are orders placed?"));
        assertThat(again.get("cached").asBoolean()).isFalse();
        assertThat(llm.calls()).isEqualTo(2);
    }

    @Test
    void llmFailureDegradesToExtractiveAnswer() {
        llm.failWith("Claude API error 529");
        JsonNode r = ask(Map.of("question", "How are orders placed today?"));
        assertThat(r.get("generator").asText()).isEqualTo("extractive");
        assertThat(r.get("warning").asText()).contains("529");
        assertThat(r.get("sources")).isNotEmpty();
    }

    @Test
    void filtersAreApplied() {
        JsonNode r = ask(Map.of("question", "requests per second", "languages", List.of("python")));
        Set<String> languages = new HashSet<>();
        r.get("sources").forEach(s -> languages.add(s.get("language").asText()));
        assertThat(languages).containsExactly("python");
    }

    @Test
    void noMatchingSourcesSkipsTheLlm() {
        JsonNode r = ask(Map.of("question", "anything at all", "languages", List.of("pdf")));
        assertThat(r.get("sources")).isEmpty();
        assertThat(r.get("answer").asText()).contains("couldn't find");
        assertThat(llm.calls()).isZero();
    }

    @Test
    void ingestEndpointNeedsTheAdminToken() {
        HttpHeaders wrong = new HttpHeaders();
        wrong.set("X-Admin-Token", "wrong");
        HttpHeaders right = new HttpHeaders();
        right.set("X-Admin-Token", "secret-token");
        Map<String, String> body = Map.of("path", ".");

        assertThat(http.postForEntity("/ingest", body, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.exchange("/ingest", HttpMethod.POST, new HttpEntity<>(body, wrong), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<JsonNode> ok = http.exchange("/ingest", HttpMethod.POST, new HttpEntity<>(body, right), JsonNode.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("files_unchanged").asInt()).isEqualTo(3);
        assertThat(http.exchange("/ingest", HttpMethod.POST, new HttpEntity<>(Map.of("path", "../.."), right), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void validationAndUi() {
        assertThat(http.postForEntity("/ask", Map.of("question", "x"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(http.postForEntity("/ask", Map.of("question", "valid question", "top_k", 50), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(http.postForEntity("/ask", Map.of("question", "valid question", "mode", "magic"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/", String.class).getBody()).contains("Codebase Assistant");
        JsonNode stats = http.getForObject("/stats", JsonNode.class);
        assertThat(stats.get("files").asInt()).isEqualTo(3);
        assertThat(stats.get("generator").asText()).isEqualTo("scripted-llm");
    }
}
