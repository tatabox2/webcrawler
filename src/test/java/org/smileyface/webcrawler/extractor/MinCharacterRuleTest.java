package org.smileyface.webcrawler.extractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinCharacterRuleTest {

    @Test
    void isMatched_trueWhenLengthEqualsOrExceedsMin() {
        Document doc = Jsoup.parse("<p>Hello World</p>");
        Element p = doc.selectFirst("p");
        MinCharacterRule rule = new MinCharacterRule(11); // "Hello World" -> 11
        assertTrue(rule.isMatched(p));

        MinCharacterRule rule2 = new MinCharacterRule(12);
        assertFalse(rule2.isMatched(p));
    }

    @Test
    void isMatched_trimsWhitespace() {
        Document doc = Jsoup.parse("<div>   abc  </div>");
        Element div = doc.selectFirst("div");
        MinCharacterRule rule = new MinCharacterRule(3);
        assertTrue(rule.isMatched(div));

        MinCharacterRule higher = new MinCharacterRule(4);
        assertFalse(higher.isMatched(div));
    }

    @Test
    void negativeThresholdTreatedAsZero_matchesAnyElement() {
        Document doc = Jsoup.parse("<span>  </span>");
        Element span = doc.selectFirst("span");
        MinCharacterRule rule = new MinCharacterRule(-5);
        assertTrue(rule.isMatched(span), "Negative min should be treated as zero, matching even empty text");
    }

    @Test
    void integration_withContentExtractor_extractsOnlyLongEnoughElements() {
        String html = """
                <html><body>
                  <div id='a'>short</div>
                  <div id='b'>this one should be extracted</div>
                  <section id='c'>
                    <p>child long enough indeed</p>
                  </section>
                </body></html>
                """;

        // Rule: min 10 chars, but exclude <body> element to avoid capturing the whole page
        ContentRule rule = (el) -> !"body".equals(el.tagName()) && new MinCharacterRule(10).isMatched(el);
        ContentExtractor extractor = new ContentExtractor();
        List<String> out = extractor.extractContent(html, List.of(rule));

        // Expect two segments: #b and the <section id='c'> (its text includes child text and should match, skipping child)
        assertEquals(2, out.size());
        assertEquals("this one should be extracted", out.get(0));
        assertTrue(out.get(1).contains("child long enough indeed"));
    }
}
