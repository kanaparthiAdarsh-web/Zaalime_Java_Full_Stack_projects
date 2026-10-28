package com.zaalime.rag.answer;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.zaalime.rag.search.ScoredChunk;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Answers with Claude through the official Anthropic Java SDK. */
public class ClaudeGenerator implements Generator {

    // if a safety classifier declines, the API retries on Anthropic's recommended fallback model
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String model;
    private final BetaOutputConfig.Effort effort;
    private final long maxTokens;

    public ClaudeGenerator(String apiKey, String model, String effort, long maxTokens) {
        this(AnthropicOkHttpClient.builder().apiKey(apiKey).build(), model, effort, maxTokens);
    }

    ClaudeGenerator(AnthropicClient client, String model, String effort, long maxTokens) {
        this.client = client;
        this.model = model;
        this.effort = BetaOutputConfig.Effort.of(effort.toLowerCase(Locale.ROOT));
        this.maxTokens = maxTokens;
    }

    @Override
    public String name() {
        return "claude:" + model;
    }

    MessageCreateParams request(String question, List<ScoredChunk> sources) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(Prompts.SYSTEM)
                .outputConfig(BetaOutputConfig.builder().effort(effort).build())
                .addBeta(FALLBACK_BETA)
                .fallbacksDefault()
                .addUserMessage(Prompts.userMessage(question, sources))
                .build();
    }

    @Override
    public String generate(String question, List<ScoredChunk> sources) {
        BetaMessage response;
        try {
            response = client.beta().messages().create(request(question, sources));
        } catch (UnauthorizedException e) {
            throw new GenerationException("Claude rejected the API key", e);
        } catch (RateLimitException e) {
            throw new GenerationException("Claude rate limit reached, try again shortly", e);
        } catch (AnthropicServiceException e) {
            throw new GenerationException("Claude API error " + e.statusCode(), e);
        } catch (AnthropicIoException e) {
            throw new GenerationException("Could not reach the Claude API", e);
        }
        return answerText(response);
    }

    static String answerText(BetaMessage response) {
        BetaStopReason stop = response.stopReason().orElse(null);
        if (BetaStopReason.REFUSAL.equals(stop)) {
            throw new GenerationException("The model declined to answer this question");
        }
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining())
                .strip();
        if (text.isEmpty()) {
            throw new GenerationException("The model returned an empty answer");
        }
        if (BetaStopReason.MAX_TOKENS.equals(stop)) {
            text += "\n\n_(answer truncated)_";
        }
        return text;
    }
}
