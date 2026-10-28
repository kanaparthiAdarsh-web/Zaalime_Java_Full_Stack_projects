package com.zaalime.rag.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.pattern.PatternTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tokenizes code the way people search it: "placeOrder" is indexed as placeorder, place and
 * order; "try_reserve" as try_reserve, try and reserve. English filler words are dropped so long
 * prose files don't win every "how does the ..." question.
 */
public class CodeAnalyzer extends Analyzer {

    static final CharArraySet STOPWORDS = new CharArraySet(Arrays.asList(("""
            a an and are as at be been but by can could did do does doing done for from has have how i if in into is it
            its me my no not of on or our should so than that the their them then there these they this to too up us was
            we were what when where which who whom why will with would you your yours about after again all also any
            because before between both during each few more most other only own same some such through under until
            very while get gets got make makes made happen happens happened use used uses using work works
            """).split("\\s+")), true);

    private static final Pattern IDENTIFIER_OR_NUMBER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|\\d+");

    private static final int SPLIT_FLAGS = WordDelimiterGraphFilter.GENERATE_WORD_PARTS
            | WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
            | WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE
            | WordDelimiterGraphFilter.PRESERVE_ORIGINAL;

    @Override
    protected TokenStreamComponents createComponents(String field) {
        Tokenizer source = new PatternTokenizer(IDENTIFIER_OR_NUMBER, 0);
        TokenStream stream = new WordDelimiterGraphFilter(source, SPLIT_FLAGS, null);
        stream = new FlattenGraphFilter(stream);
        stream = new LowerCaseFilter(stream);
        stream = new StopFilter(stream, STOPWORDS);
        return new TokenStreamComponents(source, stream);
    }

    public List<String> terms(String text) {
        List<String> terms = new ArrayList<>();
        try (TokenStream ts = tokenStream("content", text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                terms.add(term.toString());
            }
            ts.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return terms;
    }
}
