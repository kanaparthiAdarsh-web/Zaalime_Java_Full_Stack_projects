package com.zaalime.rag.answer;

import com.zaalime.rag.search.ScoredChunk;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/** Free, local alternative: any chat model served by Ollama (https://ollama.com). */
public class OllamaGenerator implements Generator {

    private record ChatResponse(Message message) {
    }

    private record Message(String role, String content) {
    }

    private final RestClient http;
    private final String model;

    public OllamaGenerator(String baseUrl, String model) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(120_000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.model = model;
    }

    @Override
    public String name() {
        return "ollama:" + model;
    }

    @Override
    public String generate(String question, List<ScoredChunk> sources) {
        var body = Map.of(
                "model", model,
                "stream", false,
                "messages", List.of(
                        new Message("system", Prompts.SYSTEM),
                        new Message("user", Prompts.userMessage(question, sources))));
        try {
            ChatResponse r = http.post().uri("/api/chat").body(body).retrieve().body(ChatResponse.class);
            String text = r == null || r.message() == null ? "" : r.message().content().strip();
            if (text.isEmpty()) {
                throw new GenerationException("Ollama returned an empty answer");
            }
            return text;
        } catch (RestClientException e) {
            throw new GenerationException("Ollama request failed: " + e.getMessage(), e);
        }
    }
}
