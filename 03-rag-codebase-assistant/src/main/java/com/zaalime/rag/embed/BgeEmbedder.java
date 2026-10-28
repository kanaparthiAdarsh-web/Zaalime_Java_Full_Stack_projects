package com.zaalime.rag.embed;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;

import java.util.ArrayList;
import java.util.List;

/**
 * BAAI/bge-small-en-v1.5 (quantised ONNX), run in-process on the CPU. The model ships inside the
 * jar, so there is no download, no API key and no per-query cost.
 */
public class BgeEmbedder implements Embedder {

    // bge models are trained with this instruction on the query side only
    private static final String QUERY_PREFIX = "Represent this sentence for searching relevant passages: ";
    private static final int BATCH = 32;

    private final BgeSmallEnV15QuantizedEmbeddingModel model = new BgeSmallEnV15QuantizedEmbeddingModel();

    @Override
    public String name() {
        return "bge-small-en-v1.5-q";
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH) {
            List<TextSegment> batch = texts.subList(i, Math.min(texts.size(), i + BATCH)).stream()
                    .map(TextSegment::from).toList();
            model.embedAll(batch).content().forEach(e -> out.add(e.vector()));
        }
        return out;
    }

    @Override
    public float[] embedQuery(String text) {
        return model.embed(QUERY_PREFIX + text).content().vector();
    }
}
