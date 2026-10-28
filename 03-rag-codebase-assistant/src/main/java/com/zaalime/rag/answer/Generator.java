package com.zaalime.rag.answer;

import com.zaalime.rag.search.ScoredChunk;

import java.util.List;

public interface Generator {

    String name();

    /** @throws GenerationException when no usable answer could be produced */
    String generate(String question, List<ScoredChunk> sources);
}
