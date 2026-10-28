package com.zaalime.rag.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerAndIdentifiersTest {

    private final CodeAnalyzer analyzer = new CodeAnalyzer();

    @Test
    void splitsIdentifiersAndDropsStopwords() {
        var terms = analyzer.terms("How does placeOrder call try_reserve on the HttpServer?");
        assertThat(terms).contains("placeorder", "place", "order", "try_reserve", "try", "reserve", "httpserver", "http", "server");
        assertThat(terms).doesNotContain("how", "does", "the", "on");
    }

    @Test
    void detectsIdentifiersInQuestions() {
        assertThat(Identifiers.in("where is reserveAndEnqueue defined, and who calls start()?"))
                .containsExactly("reserveAndEnqueue", "start");
        assertThat(Identifiers.in("explain OrderEventHandler and try_reserve")).containsExactly("OrderEventHandler", "try_reserve");
        assertThat(Identifiers.in("How does checkout prevent overselling?")).isEmpty();
    }

    @Test
    void tellsDeclarationsFromMentions() {
        assertThat(Identifiers.declares("    private CheckoutResponse reserveAndEnqueue(AuthUser user) {", "reserveAndEnqueue")).isTrue();
        assertThat(Identifiers.declares("public static <T> List<T> parseAll(String s) {", "parseAll")).isTrue();
        assertThat(Identifiers.declares("def place_order(self, item):", "place_order")).isTrue();
        assertThat(Identifiers.declares("public class OrderService {", "OrderService")).isTrue();
        assertThat(Identifiers.declares("        return reserveAndEnqueue(user, key);", "reserveAndEnqueue")).isFalse();
        assertThat(Identifiers.declares("        CheckoutResponse r = reserveAndEnqueue(user);", "reserveAndEnqueue")).isFalse();
        assertThat(Identifiers.declares("<button>Where is reserveAndEnqueue defined?</button>", "reserveAndEnqueue")).isFalse();
    }
}
