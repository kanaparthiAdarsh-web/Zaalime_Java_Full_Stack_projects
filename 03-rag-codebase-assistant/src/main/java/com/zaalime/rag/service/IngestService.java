package com.zaalime.rag.service;

import com.zaalime.rag.embed.Embedder;
import com.zaalime.rag.ingest.Chunk;
import com.zaalime.rag.ingest.CodeChunker;
import com.zaalime.rag.ingest.SourceFiles;
import com.zaalime.rag.search.KeywordIndex;
import com.zaalime.rag.service.Dtos.IngestResult;
import com.zaalime.rag.store.ChunkStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    public static class PathNotAllowedException extends RuntimeException {
        PathNotAllowedException(String message) {
            super(message);
        }
    }

    private final Path root;
    private final long maxFileBytes;
    private final CodeChunker chunker;
    private final Embedder embedder;
    private final ChunkStore store;
    private final KeywordIndex keywords;
    private final ReentrantLock lock = new ReentrantLock();

    public IngestService(Path root, long maxFileBytes, CodeChunker chunker, Embedder embedder, ChunkStore store,
                         KeywordIndex keywords) {
        this.root = root.toAbsolutePath().normalize();
        this.maxFileBytes = maxFileBytes;
        this.chunker = chunker;
        this.embedder = embedder;
        this.store = store;
        this.keywords = keywords;
        store.checkEmbeddingModel(embedder.name());
        keywords.rebuild(store.all());
    }

    Path resolve(String path) {
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) {
            throw new PathNotAllowedException("'" + path + "' is outside the allowed ingest root");
        }
        if (!Files.exists(target)) {
            throw new PathNotAllowedException("'" + path + "' does not exist");
        }
        try {
            // a symlink inside the root could still point outside it
            if (!target.toRealPath().startsWith(root.toRealPath())) {
                throw new PathNotAllowedException("'" + path + "' resolves outside the allowed ingest root");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    public IngestResult ingest(String path) {
        long started = System.nanoTime();
        Path target = resolve(path);
        lock.lock();
        try {
            Map<String, String> indexed = store.indexedFiles();
            Set<String> seen = new HashSet<>();
            int changed = 0, unchanged = 0, skipped = 0, chunksAdded = 0;

            for (Path file : SourceFiles.list(root, target, maxFileBytes)) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                seen.add(rel);
                var source = readQuietly(file, rel);
                if (source.isEmpty()) {
                    skipped++;
                    if (indexed.containsKey(rel)) { // used to be indexable, now empty or unreadable
                        store.removeFile(rel);
                        changed++;
                    }
                    continue;
                }
                if (source.get().hash().equals(indexed.get(rel))) {
                    unchanged++;
                    continue;
                }
                List<Chunk> chunks = chunker.chunk(source.get());
                List<float[]> vectors = embedder.embedDocuments(chunks.stream().map(Chunk::embeddingText).toList());
                store.replaceFile(rel, source.get().hash(), chunks, vectors);
                changed++;
                chunksAdded += chunks.size();
            }

            // files indexed under this folder before that are gone now
            String prefix = root.relativize(target).toString().replace('\\', '/');
            List<String> removed = indexed.keySet().stream()
                    .filter(p -> prefix.isEmpty() || p.equals(prefix) || p.startsWith(prefix + "/"))
                    .filter(p -> !seen.contains(p))
                    .toList();
            removed.forEach(store::removeFile);

            if (changed > 0 || !removed.isEmpty()) {
                store.bumpVersion(); // invalidates cached answers
                keywords.rebuild(store.all());
            }
            double seconds = Math.round((System.nanoTime() - started) / 1e7) / 100.0;
            return new IngestResult(seen.size(), changed, unchanged, removed.size(), skipped, chunksAdded,
                    store.count(), seconds);
        } finally {
            lock.unlock();
        }
    }

    private static java.util.Optional<SourceFiles.SourceFile> readQuietly(Path file, String rel) {
        try {
            return SourceFiles.read(file, rel);
        } catch (IOException e) {
            log.warn("Could not read {}: {}", rel, e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
