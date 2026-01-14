package org.smileyface.webcrawler.extractor;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

class ContentExtractorTest {

    @Test
    void extract_nullOrBlankHtml_returnsEmptyList() {
        ContentExtractor extractor = new ContentExtractor();
        // Use ClassNameContentRule even though HTML is null/blank to exercise the rule path
        List<String> r1 = extractor.extractContent(null, List.of(new ClassNameContentRule("lead")));
        List<String> r2 = extractor.extractContent("   ", List.of(new ClassNameContentRule("lead")));
        assertThat(r1).isEmpty();
        assertThat(r2).isEmpty();
    }

    @Test
    void extract_matchAllRules_requiresAll() {
        String html = """
                <html><body>
                  <p class='lead'>Lead paragraph</p>
                  <p>Other paragraph</p>
                  <div class='lead'>Not a paragraph</div>
                </body></html>
                """;

        ContentExtractor extractor = new ContentExtractor();
        // Only match elements that are <p> and have class 'lead'
        List<String> out = extractor.extractContent(
                html,
                null, // matchAnyRules
                List.of(new TagNameContentRule("p"), new ClassNameContentRule("lead")) // matchAllRules
        );

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isEqualTo("Lead paragraph");
    }

    @Test
    void extract_matchAnyOrAll_combined_unionAndParentSkip() {
        String html = """
                <html><body>
                  <section class='take'>
                    <h2>Heading</h2>
                    <p class='lead'>Inside Lead</p>
                    <p>Inside Para</p>
                  </section>
                  <div>
                    <p class='lead'>Outside Lead</p>
                  </div>
                </body></html>
                """;

        ContentExtractor extractor = new ContentExtractor();
        // matchAny: any element with class 'lead'
        // matchAll: section elements with class 'take'
        List<String> out = extractor.extractContent(
                html,
                List.of(new ClassNameContentRule("lead")),
                List.of(new TagNameContentRule("section"), new ClassNameContentRule("take"))
        );

        // Expect two segments:
        // 1) The whole section text (parent match via matchAll; children skipped so 'Inside Lead' paragraph is NOT separately captured)
        // 2) The outside lead paragraph (matchAny)
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).contains("Heading");
        assertThat(out.get(0)).contains("Inside Lead");
        assertThat(out.get(0)).contains("Inside Para");
        assertThat(out.get(1)).isEqualTo("Outside Lead");
    }

    @Test
    void extract_emptyRules_returnsEmptyList() {
        String html = "<html><body><p>hello</p></body></html>";
        ContentExtractor extractor = new ContentExtractor();
        assertThat(extractor.extractContent(html, null)).isEmpty();
        assertThat(extractor.extractContent(html, List.of())).isEmpty();
    }

    @Test
    void extract_withRules_collectsMatchedElementsInOrder() {
        String html = """
                <html><body>
                  <div id='main'>
                    <h1>Title</h1>
                    <p class='lead'>Lead paragraph</p>
                    <p>Other paragraph</p>
                  </div>
                  <div id='side'><p class='lead'>Sidebar lead</p></div>
                </body></html>
                """;

        // Rules: match #main and any element with class "lead" using ClassNameContentRule
        ContentRule mainDiv = (e) -> "main".equals(e.id());
        ContentRule leadByClass = new ClassNameContentRule("lead");

        ContentExtractor extractor = new ContentExtractor();
        List<String> out = extractor.extractContent(html, List.of(mainDiv, leadByClass));

        // Expect text of #main as one segment (includes its children text), followed by sidebar lead paragraph
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).startsWith("Title");
        assertThat(out.get(0)).contains("Lead paragraph");
        assertThat(out.get(1)).isEqualTo("Sidebar lead");
    }

    @Test
    void extract_nestedMatches_parentWins_childrenSkipped() {
        String html = """
                <html><body>
                  <section id='article'>
                    <h2>Heading</h2>
                    <p>Para 1</p>
                    <p class='take'>Para 2</p>
                  </section>
                </body></html>
                """;

        // Both section#article and p.take would match, but since section matches first, its children are skipped
        ContentRule articleSection = (e) -> new TagNameContentRule("section").isMatched(e) && "article".equals(e.id());
        // Demonstrate combining TagNameContentRule with ClassNameContentRule
        ContentRule takeP = (e) -> new TagNameContentRule("p").isMatched(e) && new ClassNameContentRule("take").isMatched(e);

        ContentExtractor extractor = new ContentExtractor();
        List<String> out = extractor.extractContent(html, List.of(articleSection, takeP));

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("Heading");
        assertThat(out.get(0)).contains("Para 1");
        assertThat(out.get(0)).contains("Para 2");
    }

    @Test
    void extract_realHtml_planetX_containsExpectedSegments() throws Exception {
        String html = readClasspathResource("/planet-x.html");
        assertThat(html).as("planet-x.html resource should be available on classpath").isNotNull();
        assertThat(html.length()).as("Test HTML should be non-trivial").isGreaterThan(1000);

        // Rules: capture main heading(s) and meaningful paragraphs.
        ContentRule h1 = new TagNameContentRule("h1");
        ContentRule h2 = new TagNameContentRule("h2");
        // Long paragraphs only, using TagNameContentRule("p") for tag filtering
        ContentRule longParagraph = (e) -> new TagNameContentRule("p").isMatched(e)
                && new MinCharacterRule(80).isMatched(e);

        ContentExtractor extractor = new ContentExtractor();
        List<String> out = extractor.extractContent(html, List.of(h1, h2, longParagraph));

        assertThat(out).as("Extractor should return some segments for real HTML").isNotEmpty();

        // Expect to find key headings and a known content snippet
        boolean hasMainHeading = out.stream().anyMatch(s -> s.contains("Is Planet X Real?"));
        boolean hasIntroduction = out.stream().anyMatch(s -> s.equals("Introduction"));
        boolean hasNeptuneSnippet = out.stream().anyMatch(s -> s.contains("hypothetical Neptune-sized planet"));

        assertThat(hasMainHeading).as("Should contain main H1 heading from Planet X page").isTrue();
        assertThat(hasIntroduction).as("Should contain 'Introduction' heading from page").isTrue();
        assertThat(hasNeptuneSnippet).as("Should contain a representative paragraph snippet").isTrue();
    }

    @Test
    void extract_realHtml_planetX_longParagraphs_min80_positive() throws Exception {
        String html = readClasspathResource("/planet-x.html");
        assertThat(html).as("planet-x.html resource should be available on classpath").isNotNull();
        assertThat(html.length()).as("Test HTML should be non-trivial").isGreaterThan(1000);

        // Positive test: extract only paragraphs with at least 80 characters
        ContentRule isParagraph = new TagNameContentRule("p");
        ContentRule min80 = new MinCharacterRule(80);

        ContentExtractor extractor = new ContentExtractor();
        List<String> out = extractor.extractContent(
                html,
                null, // matchAny
                List.of(isParagraph, min80) // must be a <p> and >= 80 chars
        );

        assertThat(out).as("Should extract long paragraphs (>= 80 chars)").isNotEmpty();

        // All extracted segments should have length >= 80
        assertThat(out.stream().allMatch(s -> s != null && s.trim().length() >= 80))
                .as("All extracted segments must be at least 80 characters long").isTrue();

        // Expect a known paragraph snippet to be included
        boolean hasNeptuneSnippet = out.stream().anyMatch(s -> s.contains("hypothetical Neptune-sized planet"));
        assertThat(hasNeptuneSnippet)
                .as("Should contain a representative long paragraph snippet about Neptune-sized planet").isTrue();
    }

    @Test
    void extract_realHtml_planetX_longParagraphs_min80_negative() throws Exception {
        String html = readClasspathResource("/planet-x.html");
        assertThat(html).as("planet-x.html resource should be available on classpath").isNotNull();
        assertThat(html.length()).as("Test HTML should be non-trivial").isGreaterThan(1000);

        // Negative test: using only long paragraphs rule means headings like H1/H2 should not be captured
        ContentRule isParagraph = new TagNameContentRule("p");
        ContentRule min80 = new MinCharacterRule(80);

        ContentExtractor extractor = new ContentExtractor();
        List<String> out = extractor.extractContent(
                html,
                null,
                List.of(isParagraph, min80)
        );

        // Ensure headings are NOT present
        assertThat(out.stream().noneMatch(s -> s.equals("Introduction")))
                .as("Heading 'Introduction' should not be included by paragraph+min80 filter").isTrue();
        assertThat(out.stream().noneMatch(s -> s.contains("Is Planet X Real?")))
                .as("Main H1 title should not be included by paragraph+min80 filter").isTrue();

        // Ensure no short segments slipped through
        assertThat(out.stream().allMatch(s -> s != null && s.trim().length() >= 80))
                .as("No segments shorter than 80 characters should be present").isTrue();
    }

    private static String readClasspathResource(String path) throws Exception {
        try (InputStream is = ContentExtractorTest.class.getResourceAsStream(path)) {
            if (is == null) return null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return br.lines().collect(Collectors.joining("\n"));
            }
        }
    }
}
