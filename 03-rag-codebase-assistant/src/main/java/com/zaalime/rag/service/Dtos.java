package com.zaalime.rag.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public final class Dtos {

    private Dtos() {
    }

    public record AskRequest(
            @NotBlank @Size(min = 3, max = 2000) String question,
            @JsonProperty("top_k") @Min(1) @Max(12) Integer topK,
            List<String> languages,
            String project,
            String mode) {
    }

    public record Source(int id, String path, String location, String language,
                         @JsonProperty("start_line") int startLine, @JsonProperty("end_line") int endLine,
                         double score, String snippet, boolean cited) {
    }

    public record AskResponse(String answer, List<Source> sources, String generator, boolean cached,
                              String warning, @JsonProperty("timings_ms") Map<String, Double> timingsMs) {

        AskResponse withCache(boolean cached, Map<String, Double> timings) {
            return new AskResponse(answer, sources, generator, cached, warning, timings);
        }
    }

    public record IngestRequest(@NotBlank String path) {
    }

    public record IngestResult(
            @JsonProperty("files_seen") int filesSeen,
            @JsonProperty("files_indexed") int filesIndexed,
            @JsonProperty("files_unchanged") int filesUnchanged,
            @JsonProperty("files_removed") int filesRemoved,
            @JsonProperty("files_skipped") int filesSkipped,
            @JsonProperty("chunks_added") int chunksAdded,
            @JsonProperty("total_chunks") long totalChunks,
            double seconds) {
    }
}
