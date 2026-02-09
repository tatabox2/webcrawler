package org.smileyface.webcrawler.extractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ElementStyleRuleTest {

    @Test
    void constructor_rejectsNullOrBlank() {
        assertThatThrownBy(() -> new ElementStyleRule(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ElementStyleRule("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isMatched_trueWhenStyleContainsFragment_caseInsensitive() {
        Document doc = Jsoup.parse("<p style='COLOR: red; font-weight:bold'>Hello</p>");
        Element p = doc.selectFirst("p");

        ElementStyleRule lower = new ElementStyleRule("color: red");
        ElementStyleRule upper = new ElementStyleRule("FONT-WEIGHT");

        assertThat(lower.isMatched(p)).isTrue();
        assertThat(upper.isMatched(p)).isTrue();
    }

    @Test
    void isMatched_falseWhenNoStyleOrNoMatch_orNullElement() {
        Document doc = Jsoup.parse("<div>No style</div><span style='display:block'>x</span>");
        Element div = doc.selectFirst("div");
        Element span = doc.selectFirst("span");

        ElementStyleRule r = new ElementStyleRule("display:none");
        assertThat(r.isMatched(div)).isFalse();
        assertThat(r.isMatched(span)).isFalse();
        assertThat(r.isMatched(null)).isFalse();
    }

    @Test
    void integration_withContentExtractor_selectsByStyle() {
        String html = """
                <html><body>
                  <p style='color: blue'>First</p>
                  <p>Second</p>
                  <div style='font-weight: bold'><span>Inside</span></div>
                </body></html>
                """;

        ContentExtractor extractor = new ContentExtractor();
        List<String> out = extractor.extractContent(html, List.of(
                new ElementStyleRule("color: blue"),
                new ElementStyleRule("font-weight")
        ));

        // Expect two segments: the styled <p> and the whole <div> block (children skipped)
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isEqualTo("First");
        assertThat(out.get(1)).contains("Inside");
    }
}
