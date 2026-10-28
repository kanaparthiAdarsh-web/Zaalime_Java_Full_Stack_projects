package com.zaalime.rag.answer;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps [n] citations that point at a real source, removes the rest, and reports which were used. */
public final class Citations {

    public record Result(String answer, Set<Integer> cited) {
    }

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2})]");
    // fenced blocks (possibly unterminated) and inline code are left untouched: `items[2]` is not a citation
    private static final Pattern CODE = Pattern.compile("```.*?(?:```|\\z)|`[^`\\n]*`", Pattern.DOTALL);

    private Citations() {
    }

    public static Result check(String answer, int sourceCount) {
        Set<Integer> cited = new TreeSet<>();
        StringBuilder out = new StringBuilder();
        Matcher code = CODE.matcher(answer);
        int last = 0;
        while (code.find()) {
            out.append(clean(answer.substring(last, code.start()), sourceCount, cited)).append(code.group());
            last = code.end();
        }
        out.append(clean(answer.substring(last), sourceCount, cited));
        return new Result(out.toString(), cited);
    }

    private static String clean(String text, int sourceCount, Set<Integer> cited) {
        Matcher m = CITATION.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            boolean valid = n >= 1 && n <= sourceCount;
            if (valid) {
                cited.add(n);
            }
            m.appendReplacement(sb, valid ? Matcher.quoteReplacement(m.group()) : "");
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
