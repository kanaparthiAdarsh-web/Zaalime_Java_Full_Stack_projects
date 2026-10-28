package com.zaalime.rag.search;

import com.zaalime.rag.ingest.Chunk;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * BM25 keyword search (Lucene). Catches exact identifiers that embeddings blur, e.g.
 * {@code reserveAndEnqueue}, {@code ORDER_QUEUE}, {@code PriceLevel}.
 * <p>
 * The index lives in memory and is rebuilt from Postgres after each ingest: a few thousand
 * chunks index in well under a second, and Postgres stays the single source of truth.
 */
public class KeywordIndex {

    public record Hit(Chunk chunk, float score) {
    }

    private final CodeAnalyzer analyzer = new CodeAnalyzer();
    private volatile Snapshot snapshot = new Snapshot(null, List.of());

    private record Snapshot(IndexSearcher searcher, List<Chunk> chunks) {
    }

    public synchronized void rebuild(List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            snapshot = new Snapshot(null, List.of());
            return;
        }
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                Document doc = new Document();
                doc.add(new StoredField("ord", i));
                doc.add(new StringField("language", c.language(), Field.Store.NO));
                doc.add(new StringField("project", c.project(), Field.Store.NO));
                doc.add(new TextField("content", c.path() + "\n" + c.text(), Field.Store.NO));
                writer.addDocument(doc);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            snapshot = new Snapshot(new IndexSearcher(DirectoryReader.open(dir)), List.copyOf(chunks));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int size() {
        return snapshot.chunks().size();
    }

    public List<Hit> search(String query, int limit, List<String> languages, String project) {
        Snapshot s = snapshot;
        if (s.searcher() == null) {
            return List.of();
        }
        var terms = new LinkedHashSet<>(analyzer.terms(query));
        if (terms.isEmpty()) {
            return List.of();
        }
        BooleanQuery.Builder q = new BooleanQuery.Builder();
        BooleanQuery.Builder anyTerm = new BooleanQuery.Builder();
        terms.forEach(t -> anyTerm.add(new TermQuery(new Term("content", t)), BooleanClause.Occur.SHOULD));
        q.add(anyTerm.build(), BooleanClause.Occur.MUST);
        if (languages != null && !languages.isEmpty()) {
            q.add(new TermInSetQuery("language", languages.stream().map(BytesRef::new).toList()),
                    BooleanClause.Occur.FILTER);
        }
        if (project != null && !project.isBlank()) {
            q.add(new TermQuery(new Term("project", project)), BooleanClause.Occur.FILTER);
        }
        try {
            List<Hit> hits = new ArrayList<>();
            var storedFields = s.searcher().storedFields();
            for (ScoreDoc sd : s.searcher().search(q.build(), limit).scoreDocs) {
                int ord = storedFields.document(sd.doc).getField("ord").numericValue().intValue();
                hits.add(new Hit(s.chunks().get(ord), sd.score));
            }
            return hits;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> analyze(String text) {
        return analyzer.terms(text);
    }
}
