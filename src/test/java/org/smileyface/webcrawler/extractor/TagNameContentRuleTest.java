package org.smileyface.webcrawler.extractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TagNameContentRuleTest {

    @Test
    void isMatched_trueForMatchingTag_caseInsensitive() {
        Document doc = Jsoup.parse("<H1>Title</H1><p>Para</p>");
        Element h1 = doc.selectFirst("h1");

        TagNameContentRule ruleLower = new TagNameContentRule("h1");
        TagNameContentRule ruleUpper = new TagNameContentRule("H1");

        assertThat(ruleLower.isMatched(h1)).isTrue();
        assertThat(ruleUpper.isMatched(h1)).isTrue();
    }

    @Test
    void isMatched_falseForNonMatchingTag_orNullElement() {
        Document doc = Jsoup.parse("<div>Div</div>");
        Element div = doc.selectFirst("div");
        TagNameContentRule rule = new TagNameContentRule("p");

        assertThat(rule.isMatched(div)).isFalse();
        assertThat(rule.isMatched(null)).isFalse();
    }

    @Test
    void constructor_rejectsNullOrBlankTagName() {
        assertThatThrownBy(() -> new TagNameContentRule(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TagNameContentRule("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void integration_withContentExtractor_selectsByTag() {
        String html = """
                <html><body>
                  <h1>Main</h1>
                  <div>
                    <p>First</p>
                    <p>Second</p>
                  </div>
                </body></html>
                """;

        ContentExtractor extractor = new ContentExtractor();
        List<String> out = extractor.extractContent(html, List.of(
                new TagNameContentRule("h1"),
                new TagNameContentRule("p")
        ));

        // Expect 3 segments: h1, first p, second p (in document order)
        assertThat(out).hasSize(3);
        assertThat(out.get(0)).isEqualTo("Main");
        assertThat(out.get(1)).isEqualTo("First");
        assertThat(out.get(2)).isEqualTo("Second");
    }
}
