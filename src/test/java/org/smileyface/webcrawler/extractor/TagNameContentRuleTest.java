package org.smileyface.webcrawler.extractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagNameContentRuleTest {

    @Test
    void isMatched_trueForMatchingTag_caseInsensitive() {
        Document doc = Jsoup.parse("<H1>Title</H1><p>Para</p>");
        Element h1 = doc.selectFirst("h1");

        TagNameContentRule ruleLower = new TagNameContentRule("h1");
        TagNameContentRule ruleUpper = new TagNameContentRule("H1");

        assertTrue(ruleLower.isMatched(h1));
        assertTrue(ruleUpper.isMatched(h1));
    }

    @Test
    void isMatched_falseForNonMatchingTag_orNullElement() {
        Document doc = Jsoup.parse("<div>Div</div>");
        Element div = doc.selectFirst("div");
        TagNameContentRule rule = new TagNameContentRule("p");

        assertFalse(rule.isMatched(div));
        assertFalse(rule.isMatched(null));
    }

    @Test
    void constructor_rejectsNullOrBlankTagName() {
        assertThrows(IllegalArgumentException.class, () -> new TagNameContentRule(null));
        assertThrows(IllegalArgumentException.class, () -> new TagNameContentRule("   "));
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
        assertEquals(3, out.size());
        assertEquals("Main", out.get(0));
        assertEquals("First", out.get(1));
        assertEquals("Second", out.get(2));
    }
}
