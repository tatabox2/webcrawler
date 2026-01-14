package org.smileyface.webcrawler.extractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MinCharacterRuleTest {

    @Test
    void isMatched_trueWhenLengthEqualsOrExceedsMin() {
        Document doc = Jsoup.parse("<p>Hello World</p>");
        Element p = doc.selectFirst("p");
        MinCharacterRule rule = new MinCharacterRule(11); // "Hello World" -> 11
        assertThat(rule.isMatched(p)).isTrue();

        MinCharacterRule rule2 = new MinCharacterRule(12);
        assertThat(rule2.isMatched(p)).isFalse();
    }

    @Test
    void isMatched_trimsWhitespace() {
        Document doc = Jsoup.parse("<div>   abc  </div>");
        Element div = doc.selectFirst("div");
        MinCharacterRule rule = new MinCharacterRule(3);
        assertThat(rule.isMatched(div)).isTrue();

        MinCharacterRule higher = new MinCharacterRule(4);
        assertThat(higher.isMatched(div)).isFalse();
    }

    @Test
    void negativeThresholdTreatedAsZero_matchesAnyElement() {
        Document doc = Jsoup.parse("<span>  </span>");
        Element span = doc.selectFirst("span");
        MinCharacterRule rule = new MinCharacterRule(-5);
        assertThat(rule.isMatched(span)).as("Negative min should be treated as zero, matching even empty text").isTrue();
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
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isEqualTo("this one should be extracted");
        assertThat(out.get(1)).contains("child long enough indeed");
    }
}
