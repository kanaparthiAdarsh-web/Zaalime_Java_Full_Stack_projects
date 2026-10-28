package com.zaalime.rag.ingest;

import com.zaalime.rag.ingest.SourceFiles.Section;
import com.zaalime.rag.ingest.SourceFiles.SourceFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits files into overlapping chunks of whole lines.
 * <p>
 * Working on lines (not characters) means every chunk maps to an exact line range and keeps
 * its indentation. Within the size budget the chunker cuts at the best boundary it can find:
 * the start of a class / method / heading first, then a blank line, then any line.
 */
public class CodeChunker {

    private static final Set<String> CODE = Set.of("java", "kotlin", "js", "ts", "go", "python", "lua", "shell");

    // a declaration (or the annotation / doc comment that opens one), at any indentation
    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*(?:/\\*\\*|@\\w+|(?:public|private|protected|static|final|abstract|sealed|default|export|async)\\b"
                    + "|(?:class|interface|enum|record|def|function|func|fn|local\\s+function)\\s)");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s");

    private static final int DECLARATION_START = 3;
    private static final int PARAGRAPH = 2;
    private static final int ANY_LINE = 1;

    private final int maxChars;
    private final int overlapChars;

    public CodeChunker(int maxChars, int overlapChars) {
        if (overlapChars >= maxChars) {
            throw new IllegalArgumentException("overlap must be smaller than the chunk size");
        }
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public List<Chunk> chunk(SourceFile file) {
        String project = file.path().contains("/") ? file.path().substring(0, file.path().indexOf('/')) : ".";
        List<Chunk> chunks = new ArrayList<>();
        for (Section section : file.sections()) {
            if ("pdf".equals(file.language())) {
                for (String piece : splitProse(section.text())) {
                    chunks.add(newChunk(file, project, piece, section.firstLine(), section.firstLine(), chunks.size()));
                }
            } else {
                chunkLines(file, project, section, chunks);
            }
        }
        return chunks;
    }

    private void chunkLines(SourceFile file, String project, Section section, List<Chunk> out) {
        String[] lines = section.text().split("\n", -1);
        int[] priority = boundaryPriorities(lines, file.language());
        int start = 0;
        while (start < lines.length) {
            start = skipBlank(lines, start);
            if (start >= lines.length) {
                break;
            }
            // furthest line that still fits
            int end = start;
            int size = 0;
            while (end < lines.length && (end == start || size + lines[end].length() + 1 <= maxChars)) {
                size += lines[end].length() + 1;
                end++;
            }
            int cut = end;
            if (end < lines.length) {
                cut = bestCut(priority, start, end);
            }
            int last = trimTrailingBlank(lines, start, cut);
            String text = String.join("\n", java.util.Arrays.copyOfRange(lines, start, last));
            if (!text.isBlank()) {
                int firstLine = section.firstLine() + start;
                out.add(newChunk(file, project, text, firstLine, firstLine + (last - start) - 1, out.size()));
            }
            if (cut >= lines.length) {
                break;
            }
            start = Math.max(overlapStart(lines, cut), start + 1);
        }
    }

    /** Cut before the strongest boundary in the second half of the window; the latest one wins ties. */
    private static int bestCut(int[] priority, int start, int end) {
        int best = end;
        int bestPriority = 0;
        for (int i = start + Math.max(1, (end - start) / 2); i < end; i++) {
            if (priority[i] >= bestPriority) {
                best = i;
                bestPriority = priority[i];
            }
        }
        return best;
    }

    /** Back up from the cut by roughly overlapChars, on a line boundary. */
    private int overlapStart(String[] lines, int cut) {
        int s = cut;
        int size = 0;
        while (s > 0 && size + lines[s - 1].length() + 1 <= overlapChars) {
            size += lines[s - 1].length() + 1;
            s--;
        }
        return s;
    }

    private static int[] boundaryPriorities(String[] lines, String language) {
        int[] p = new int[lines.length];
        boolean code = CODE.contains(language);
        boolean markdown = "markdown".equals(language);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String prev = i > 0 ? lines[i - 1].strip() : "";
            if (line.isBlank()) {
                p[i] = PARAGRAPH;
            } else if (markdown && (HEADING.matcher(line).find() || line.startsWith("```"))) {
                p[i] = HEADING.matcher(line).find() ? DECLARATION_START : PARAGRAPH;
            } else if (code && DECLARATION.matcher(line).find() && !continuesDeclaration(prev)) {
                p[i] = DECLARATION_START;
            } else {
                p[i] = ANY_LINE;
            }
        }
        return p;
    }

    // don't separate a method from the annotation or doc comment right above it
    private static boolean continuesDeclaration(String previousLine) {
        return previousLine.startsWith("@") || previousLine.startsWith("*") || previousLine.endsWith("*/")
                || previousLine.startsWith("/**");
    }

    private static int skipBlank(String[] lines, int i) {
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        return i;
    }

    private static int trimTrailingBlank(String[] lines, int start, int end) {
        while (end > start && lines[end - 1].isBlank()) {
            end--;
        }
        return end;
    }

    /** PDF pages have no useful line structure; split on sentence ends instead. */
    private List<String> splitProse(String text) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length()) {
                int sentence = text.lastIndexOf(". ", end);
                if (sentence > start + maxChars / 2) {
                    end = sentence + 1;
                }
            }
            pieces.add(text.substring(start, end).strip());
            if (end >= text.length()) {
                break;
            }
            int next = text.indexOf(' ', Math.max(start + 1, end - overlapChars));
            start = next < 0 || next >= end ? end : next + 1;
        }
        return pieces;
    }

    private static Chunk newChunk(SourceFile file, String project, String text, int startLine, int endLine, int index) {
        String id = sha1(file.path() + "|" + startLine + "|" + index + "|" + file.hash());
        return new Chunk(id, file.path(), project, file.language(), text, startLine, endLine, file.hash());
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
