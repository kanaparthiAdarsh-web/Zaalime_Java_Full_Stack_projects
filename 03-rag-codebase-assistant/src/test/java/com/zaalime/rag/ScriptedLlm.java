package com.zaalime.rag;

import com.zaalime.rag.answer.GenerationException;
import com.zaalime.rag.answer.Generator;
import com.zaalime.rag.search.ScoredChunk;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Stands in for Claude in integration tests: returns a fixed answer or fails on demand. */
public class ScriptedLlm implements Generator {

    public static final String DEFAULT_ANSWER = "placeOrder reserves stock first [1]. See also [7].";

    private volatile String answer = DEFAULT_ANSWER;
    private volatile String failure;
    private final AtomicInteger calls = new AtomicInteger();

    public void reset() {
        answer = DEFAULT_ANSWER;
        failure = null;
        calls.set(0);
    }

    public void failWith(String message) {
        failure = message;
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public String name() {
        return "scripted-llm";
    }

    @Override
    public String generate(String question, List<ScoredChunk> sources) {
        calls.incrementAndGet();
        if (failure != null) {
            throw new GenerationException(failure);
        }
        return answer;
    }
}
