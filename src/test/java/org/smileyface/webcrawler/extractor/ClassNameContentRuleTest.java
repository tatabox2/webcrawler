package org.smileyface.webcrawler.extractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassNameContentRuleTest {

    @Test
    void isMatched_trueWhenElementHasClass_amongMultiple() {
        Document doc = Jsoup.parse("<div class='a b c'>X</div>");
        Element div = doc.selectFirst("div");
        ClassNameContentRule rule = new ClassNameContentRule("b");
        assertTrue(rule.isMatched(div));
    }

    @Test
    void isMatched_falseWhenMissingOrNull() {
        Document doc = Jsoup.parse("<p class='alpha beta'>Text</p>");
        Element p = doc.selectFirst("p");
        ClassNameContentRule rule = new ClassNameContentRule("gamma");
        assertFalse(rule.isMatched(p));
        assertFalse(rule.isMatched(null));
    }

    @Test
    void constructor_rejectsNullOrBlank() {
        assertThrows(IllegalArgumentException.class, () -> new ClassNameContentRule(null));
        assertThrows(IllegalArgumentException.class, () -> new ClassNameContentRule("   "));
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

        assertEquals(3, out.size());
        assertEquals("Lead one", out.get(0));
        assertEquals("Lead two", out.get(1));
        assertEquals("Lead three", out.get(2));
    }
}
