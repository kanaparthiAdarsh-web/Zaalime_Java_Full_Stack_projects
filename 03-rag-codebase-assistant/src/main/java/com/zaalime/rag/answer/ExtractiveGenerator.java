package com.zaalime.rag.answer;

import com.zaalime.rag.search.CodeAnalyzer;
import com.zaalime.rag.search.ScoredChunk;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** No LLM configured (or it failed): answer with the best line of each source. Never hallucinates. */
public class ExtractiveGenerator implements Generator {

    private static final Pattern COMMENT_ONLY = Pattern.compile("^(/\\*+|\\*+/?|//+|#+|<!--|-->|[{}()\\[\\];,]*)$");

    private final CodeAnalyzer analyzer = new CodeAnalyzer();

    @Override
    public String name() {
        return "extractive";
    }

    @Override
    public String generate(String question, List<ScoredChunk> sources) {
        Set<String> terms = new HashSet<>(analyzer.terms(question));
        StringBuilder sb = new StringBuilder("No language model is configured, so here are the most relevant passages:\n\n");
        for (int i = 0; i < sources.size(); i++) {
            var c = sources.get(i).chunk();
            String file = c.path().substring(c.path().lastIndexOf('/') + 1);
            String where = "pdf".equals(c.language()) ? file + " p." + c.startLine() : file + ":" + c.startLine() + "-" + c.endLine();
            String line = bestLine(c.text(), terms);
            sb.append("- `").append(where).append("`: ")
                    .append(line, 0, Math.min(line.length(), 160))
                    .append(" [").append(i + 1).append("]\n");
        }
        return sb.toString().strip();
    }

    /** The line sharing the most words with the question; skips bare comment markers and braces. */
    String bestLine(String text, Set<String> terms) {
        String best = "";
        int bestScore = -1;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || COMMENT_ONLY.matcher(line).matches()) {
                continue;
            }
            Set<String> words = new HashSet<>(analyzer.terms(line));
            words.retainAll(terms);
            if (words.size() > bestScore) {
                best = line.replaceFirst("^[*/# ]+", "").strip();
                bestScore = words.size();
            }
        }
        return best;
    }
}
