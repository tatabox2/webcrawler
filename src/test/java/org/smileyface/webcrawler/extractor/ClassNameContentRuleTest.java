package org.smileyface.webcrawler.extractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ClassNameContentRuleTest {

    @Test
    void isMatched_trueWhenElementHasClass_amongMultiple() {
        Document doc = Jsoup.parse("<div class='a b c'>X</div>");
        Element div = doc.selectFirst("div");
        ClassNameContentRule rule = new ClassNameContentRule("b");
        assertThat(rule.isMatched(div)).isTrue();
    }

    @Test
    void isMatched_falseWhenMissingOrNull() {
        Document doc = Jsoup.parse("<p class='alpha beta'>Text</p>");
        Element p = doc.selectFirst("p");
        ClassNameContentRule rule = new ClassNameContentRule("gamma");
        assertThat(rule.isMatched(p)).isFalse();
        assertThat(rule.isMatched(null)).isFalse();
    }

    @Test
    void constructor_rejectsNullOrBlank() {
        assertThatThrownBy(() -> new ClassNameContentRule(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassNameContentRule("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void integration_withContentExtractor_selectsByClassInOrder() {
        String html = """
                <html><body>
                  <div class='lead'>Lead one</div>
                  <section>
                    <p class='lead highlight'>Lead two</p>
                    <p>Other</p>
                  </section>
                  <footer class='lead'>Lead three</footer>
                </body></html>
                """;

        ContentExtractor extractor = new ContentExtractor();
        List<String> out = extractor.extractContent(html, List.of(new ClassNameContentRule("lead")));

        assertThat(out).hasSize(3);
        assertThat(out.get(0)).isEqualTo("Lead one");
        assertThat(out.get(1)).isEqualTo("Lead two");
        assertThat(out.get(2)).isEqualTo("Lead three");
    }
}
