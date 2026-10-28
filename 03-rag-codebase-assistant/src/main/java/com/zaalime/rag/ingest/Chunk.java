package com.zaalime.rag.ingest;

/**
 * A piece of a file. Lines are 1-based and inclusive; for PDFs both are the page number.
 */
public record Chunk(String id, String path, String project, String language, String text,
                    int startLine, int endLine, String fileHash) {

    public String location() {
        return "pdf".equals(language) ? path + " (page " + startLine + ")" : path + ":" + startLine + "-" + endLine;
    }

    /** What gets embedded: the path says a lot about a chunk that the code alone doesn't (which class, which module). */
    public String embeddingText() {
        return "File: " + path + " (" + language + ")\n\n" + text;
    }
}
