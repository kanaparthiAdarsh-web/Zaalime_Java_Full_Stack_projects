package com.zaalime.rag.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Spots code names in a question, and checks whether a chunk declares one of them. */
public final class Identifiers {

    // camelCase, PascalCase with an inner capital, snake_case, or call()
    private static final Pattern IDENTIFIER = Pattern.compile(
            "\\b(?:[a-z]+[A-Z]\\w*|[A-Z][a-z]+[A-Z]\\w*|\\w+_\\w+|\\w+\\(\\))");

    private static final Set<String> NOT_A_DECLARATION = Set.of(
            "return", "new", "throw", "await", "yield", "if", "while", "else", "case", "assert");

    private Identifiers() {
    }

    public static List<String> in(String question) {
        List<String> found = new ArrayList<>();
        Matcher m = IDENTIFIER.matcher(question);
        while (m.find()) {
            found.add(m.group().replace("()", ""));
        }
        return found;
    }

    /**
     * Rough check that the text declares {@code name} rather than just mentioning it: catches
     * {@code def x(}, {@code class X}, {@code function x(} and Java-style {@code public Foo x(};
     * skips calls such as {@code return x(...)} or {@code y = x(...)}.
     */
    public static boolean declares(String text, String name) {
        String n = Pattern.quote(name);
        if (Pattern.compile("\\b(?:def|class|interface|enum|record|function|struct|type)\\s+" + n + "\\b")
                .matcher(text).find()) {
            return true;
        }
        Matcher method = Pattern.compile("(?m)^\\s*((?:[\\w@<>\\[\\],.?]+\\s+)+)" + n + "\\s*\\(").matcher(text);
        while (method.find()) {
            String before = method.group(1).strip();
            String firstWord = before.split("\\s+")[0];
            if (!NOT_A_DECLARATION.contains(firstWord) && !before.contains("=")) {
                return true;
            }
        }
        return false;
    }
}
