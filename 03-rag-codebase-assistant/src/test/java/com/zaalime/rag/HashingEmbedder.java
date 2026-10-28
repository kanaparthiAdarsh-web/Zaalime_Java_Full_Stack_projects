package com.zaalime.rag;

import com.zaalime.rag.embed.Embedder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic bag-of-words vectors: lets the integration tests run without loading the ONNX model. */
public class HashingEmbedder implements Embedder {

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    @Override
    public String name() {
        return "hashing-test";
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return texts.stream().map(this::embedQuery).toList();
    }

    @Override
    public float[] embedQuery(String text) {
        float[] v = new float[DIMENSIONS];
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            v[Math.floorMod(hash(m.group()), DIMENSIONS)] += 1f;
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            v[0] = 1f; // pgvector can't compute cosine distance of a zero vector
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] /= (float) norm;
        }
        return v;
    }

    private static int hash(String word) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(word.getBytes(StandardCharsets.UTF_8));
            return ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
