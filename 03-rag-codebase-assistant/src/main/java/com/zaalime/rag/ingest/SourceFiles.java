package com.zaalime.rag.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Finds the files worth indexing and reads them as text (or text per PDF page). */
public final class SourceFiles {

    private static final Logger log = LoggerFactory.getLogger(SourceFiles.class);

    static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("java", "java"), Map.entry("kt", "kotlin"), Map.entry("py", "python"),
            Map.entry("md", "markdown"), Map.entry("markdown", "markdown"), Map.entry("pdf", "pdf"),
            Map.entry("js", "js"), Map.entry("ts", "ts"), Map.entry("go", "go"), Map.entry("sql", "sql"),
            Map.entry("yml", "yaml"), Map.entry("yaml", "yaml"), Map.entry("lua", "lua"), Map.entry("sh", "shell"),
            Map.entry("xml", "xml"), Map.entry("html", "html"), Map.entry("css", "css"), Map.entry("txt", "text"));

    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", "target", "build", "dist", "bin", "out", "__pycache__", "venv");

    /** One readable piece of a file: the whole text for normal files, one page for PDFs. */
    public record Section(String text, int firstLine) {
    }

    public record SourceFile(String path, String language, String hash, List<Section> sections) {
    }

    private SourceFiles() {
    }

    /** Relative paths (with forward slashes) of every supported file under target. */
    public static List<Path> list(Path root, Path target, long maxBytes) {
        if (Files.isRegularFile(target)) {
            return supported(root, target, maxBytes) ? List.of(target) : List.of();
        }
        try (Stream<Path> walk = Files.walk(target)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> supported(root, p, maxBytes))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean supported(Path root, Path file, long maxBytes) {
        if (language(file).isEmpty()) {
            return false;
        }
        Path rel = root.relativize(file);
        for (int i = 0; i < rel.getNameCount() - 1; i++) {
            String dir = rel.getName(i).toString();
            if (dir.startsWith(".") || SKIP_DIRS.contains(dir)) {
                return false;
            }
        }
        try {
            if (Files.size(file) > maxBytes) {
                log.info("Skipping {}: larger than {} bytes", rel, maxBytes);
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    static Optional<String> language(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? Optional.empty() : Optional.ofNullable(LANGUAGES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }

    /** Empty when the file has no usable text (binary, blank, unreadable PDF). */
    public static Optional<SourceFile> read(Path file, String relPath) throws IOException {
        String language = language(file).orElseThrow();
        byte[] bytes = Files.readAllBytes(file);
        String hash = sha256(bytes);
        List<Section> sections = "pdf".equals(language) ? pdfPages(bytes, relPath) : textSection(bytes);
        boolean empty = sections.stream().allMatch(s -> s.text().isBlank());
        return empty ? Optional.empty() : Optional.of(new SourceFile(relPath, language, hash, sections));
    }

    private static List<Section> textSection(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 4096); i++) {
            if (bytes[i] == 0) {
                return List.of(); // binary file with a text-ish extension
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        return List.of(new Section(text, 1));
    }

    private static List<Section> pdfPages(byte[] bytes, String relPath) {
        List<Section> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc).replaceAll("\\s+", " ").strip();
                if (!text.isEmpty()) {
                    pages.add(new Section(text, page));
                }
            }
        } catch (IOException e) {
            // a corrupt or encrypted PDF must not stop the whole ingest
            log.warn("Could not read PDF {}: {}", relPath, e.getMessage());
        }
        return pages;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
