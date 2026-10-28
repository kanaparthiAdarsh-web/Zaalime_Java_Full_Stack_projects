package com.zaalime.rag.embed;

import java.util.List;

public interface Embedder {

    /** Must match the vector(...) size in the schema. */
    int DIMENSIONS = 384;

    String name();

    List<float[]> embedDocuments(List<String> texts);

    float[] embedQuery(String text);
}
