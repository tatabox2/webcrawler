package org.smileyface.webcrawler.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class WebPageContentTest {

    @Test
    void computeHash_isDeterministicAndSensitiveToInputs() {
        String url = "https://example.com/a";
        String content1 = "hello";
        String content2 = "hello!";

        String h1 = WebPageContent.computeHash(url, content1);
        String h1Again = WebPageContent.computeHash(url, content1);
        String h2 = WebPageContent.computeHash(url, content2);

        assertThat(h1Again).as("Hash should be deterministic for same inputs").isEqualTo(h1);
        assertThat(h2).as("Hash should change when content changes").isNotEqualTo(h1);
    }

    @Test
    void constructor_ignoresProvidedHashAndUsesUrlPlusContent() {
        String url = "https://example.com/x";
        String content = "Some content";

        WebPageContent doc = new WebPageContent(
                null, url, "example.com", System.currentTimeMillis(), CrawlStatus.OK,
                200, 123L, 1, "title", "desc", List.of(content), content.length(),
                "text/html", "en", List.of(), "this-will-be-ignored"
        );

        assertThat(doc.getHash()).isEqualTo(WebPageContent.computeHash(url, content));
    }

    @Test
    void setters_recomputeHashOnUrlOrContentChange() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/y");
        doc.setContents(List.of("A"));
        String h1 = doc.getHash();
        assertThat(h1).isEqualTo(WebPageContent.computeHash(doc.getUrl(), doc.getContents()));

        doc.setContents(List.of("B"));
        String h2 = doc.getHash();
        assertThat(h2).isEqualTo(WebPageContent.computeHash(doc.getUrl(), doc.getContents()));
        assertThat(h2).as("Changing content should change hash").isNotEqualTo(h1);

        doc.setUrl("https://example.com/z");
        String h3 = doc.getHash();
        assertThat(h3).isEqualTo(WebPageContent.computeHash(doc.getUrl(), doc.getContents()));
        assertThat(h3).as("Changing URL should change hash").isNotEqualTo(h2);
    }

    @Test
    void computeHash_handlesNullsAsEmptyStrings() {
        // String-based variant
        String a = WebPageContent.computeHash(null, (String) null);
        String b = WebPageContent.computeHash("", "");
        String c = WebPageContent.computeHash("", (String) null);
        String d = WebPageContent.computeHash(null, "");

        assertThat(b).isEqualTo(a);
        assertThat(c).isEqualTo(b);
        assertThat(d).isEqualTo(c);

        // List-based variant should be equivalent when contents are empty or null
        String la = WebPageContent.computeHash(null, (java.util.List<String>) null);
        String lb = WebPageContent.computeHash("", java.util.List.of());
        String lc = WebPageContent.computeHash("", (java.util.List<String>) null);
        String ld = WebPageContent.computeHash(null, java.util.List.of());

        assertThat(lb).isEqualTo(la);
        assertThat(lc).isEqualTo(lb);
        assertThat(ld).isEqualTo(lc);
        // And equal to the string-based empty content hash
        assertThat(la).isEqualTo(a);
    }

    @Test
    void addContents_string_updatesHash() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/hash");
        doc.setContents(List.of("A"));
        String h1 = doc.getHash();

        doc.addContents("B");

        String h2 = doc.getHash();
        assertThat(h2).as("Hash should change after adding a content segment").isNotEqualTo(h1);
        assertThat(h2).isEqualTo(WebPageContent.computeHash(doc.getUrl(), doc.getContents()));
    }

    @Test
    void addContents_list_updatesHash_andEmptyIsNoOp() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/hash2");
        doc.setContents(List.of("one"));
        String h1 = doc.getHash();

        doc.addContents(List.of("two", "three"));
        String h2 = doc.getHash();
        assertThat(h2).as("Hash should change after adding multiple content segments").isNotEqualTo(h1);
        assertThat(h2).isEqualTo(WebPageContent.computeHash(doc.getUrl(), doc.getContents()));

        // Empty list should be a no-op and not change the hash
        doc.addContents(List.of());
        String h3 = doc.getHash();
        assertThat(h3).as("Adding an empty list should not change the hash").isEqualTo(h2);
    }

    @Test
    void updateContents_list_replacesContentsAndUpdatesHash() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/hash3");
        doc.setContents(List.of("old1", "old2"));
        String h1 = doc.getHash();

        List<String> newContents = List.of("new1", "new2");
        doc.updateContents(newContents);

        String h2 = doc.getHash();
        assertThat(h2).as("Hash should change after replacing contents").isNotEqualTo(h1);
        assertThat(doc.getContents()).as("Contents should be replaced by updateContents(List)").isEqualTo(newContents);
        assertThat(h2).isEqualTo(WebPageContent.computeHash(doc.getUrl(), doc.getContents()));
    }

    @Test
    void updateContents_index_overridesEntireListAndUpdatesHash() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/override");
        doc.setContents(List.of("old1", "old2"));
        String before = doc.getHash();

        doc.updateContents(5, "only"); // index is ignored by spec

        List<String> expected = List.of("only");
        assertThat(doc.getContents()).as("Contents should be overridden to a single element").isEqualTo(expected);
        assertThat(doc.getHash()).as("Hash should change after overriding contents").isNotEqualTo(before);
        assertThat(doc.getHash()).isEqualTo(WebPageContent.computeHash(doc.getUrl(), expected));
    }

    @Test
    void gettersSetters_roundTripAndDefensiveCopies() {
        WebPageContent doc = new WebPageContent();

        // Primitive/value fields
        long now = System.currentTimeMillis();
        doc.setId("id-123");
        doc.setUrl("https://example.com/");
        doc.setDomain("example.com");
        doc.setCrawlTimestamp(now);
        doc.setStatus(CrawlStatus.OK);
        doc.setHttpStatus(200);
        doc.setFetchDurationMs(321L);
        doc.setCrawlDepth(2);
        doc.setTitle("Hello");
        doc.setDescription("Desc");
        doc.setContentLength(42);
        doc.setContentType("text/html");
        doc.setLanguage("en");

        assertThat(doc.getId()).isEqualTo("id-123");
        assertThat(doc.getUrl()).isEqualTo("https://example.com/");
        assertThat(doc.getDomain()).isEqualTo("example.com");
        assertThat(doc.getCrawlTimestamp()).isEqualTo(now);
        assertThat(doc.getStatus()).isEqualTo(CrawlStatus.OK);
        assertThat(doc.getHttpStatus()).isEqualTo(200);
        assertThat(doc.getFetchDurationMs()).isEqualTo(321L);
        assertThat(doc.getCrawlDepth()).isEqualTo(2);
        assertThat(doc.getTitle()).isEqualTo("Hello");
        assertThat(doc.getDescription()).isEqualTo("Desc");
        assertThat(doc.getContentLength()).isEqualTo(42);
        assertThat(doc.getContentType()).isEqualTo("text/html");
        assertThat(doc.getLanguage()).isEqualTo("en");

        // Defensive copy on setContents
        java.util.ArrayList<String> contentsSrc = new java.util.ArrayList<>(List.of("a", "b"));
        doc.setContents(contentsSrc);
        contentsSrc.add("mutate");
        assertThat(doc.getContents()).as("Internal contents should not reflect external list mutations").isEqualTo(List.of("a", "b"));

        // Defensive copy on setOutLinks
        java.util.ArrayList<String> outLinksSrc = new java.util.ArrayList<>(List.of("l1", "l2"));
        doc.setOutLinks(outLinksSrc);
        outLinksSrc.add("l3");
        assertThat(doc.getOutLinks()).as("Internal outLinks should not reflect external list mutations").isEqualTo(List.of("l1", "l2"));
    }

    @Test
    void constructor_defensiveCopiesAndHashFromUrlAndContents() {
        String url = "https://example.com/copy";
        java.util.ArrayList<String> contents = new java.util.ArrayList<>(List.of("x", "y"));
        java.util.ArrayList<String> outLinks = new java.util.ArrayList<>(List.of("a", "b"));

        WebPageContent doc = new WebPageContent(
                "doc-id", url, "example.com", System.currentTimeMillis(), CrawlStatus.OK,
                200, 10L, 0, "t", "d", contents, 2, "text/html", "en", outLinks, "ignored"
        );

        // Mutate the source lists after construction; internal state should not change
        contents.add("z");
        outLinks.add("c");

        assertThat(doc.getContents()).as("Constructor must defensively copy contents").isEqualTo(List.of("x", "y"));
        assertThat(doc.getOutLinks()).as("Constructor must defensively copy outLinks").isEqualTo(List.of("a", "b"));
        assertThat(doc.getHash()).as("Hash must be derived from url+contents").isEqualTo(WebPageContent.computeHash(url, List.of("x", "y")));
    }

    @Test
    void computeHash_listOrderAndSegmentSeparationMatters() {
        String url = "u";
        String h1 = WebPageContent.computeHash(url, List.of("a", "b"));
        String h2 = WebPageContent.computeHash(url, List.of("b", "a"));
        assertThat(h2).as("Order of segments should affect the hash").isNotEqualTo(h1);

        String hx = WebPageContent.computeHash(url, List.of("ab", "c"));
        String hy = WebPageContent.computeHash(url, List.of("a", "bc"));
        assertThat(hy).as("Segment separator must avoid concatenation collisions").isNotEqualTo(hx);
    }

    @Test
    void addContents_null_isStoredAndHashUpdated() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/null");
        doc.setContents(List.of("x"));
        String before = doc.getHash();

        doc.addContents((String) null);

        assertThat(doc.getContents().size()).isEqualTo(2);
        assertThat(doc.getContents().get(1)).isNull();
        assertThat(doc.getHash()).isNotEqualTo(before);
        assertThat(doc.getHash()).isEqualTo(WebPageContent.computeHash(doc.getUrl(), java.util.Arrays.asList("x", null)));
    }

    @Test
    void setContents_nullClearsContentsAndRecomputesHash() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/clear");
        doc.setContents(List.of("something"));
        String expected = WebPageContent.computeHash(doc.getUrl(), (List<String>) null);

        doc.setContents(null);

        assertThat(doc.getContents()).as("Contents should become null").isNull();
        assertThat(doc.getHash()).as("Hash should be recomputed with null contents").isEqualTo(expected);
    }

    @Test
    void setOutLinks_nullAllowed() {
        WebPageContent doc = new WebPageContent();
        doc.setOutLinks(null);
        assertThat(doc.getOutLinks()).isNull();
    }

    @Test
    void equalsAndHashCode_semantics() {
        WebPageContent a = new WebPageContent();
        a.setId("X");
        a.setUrl("https://a");

        WebPageContent b = new WebPageContent();
        b.setId("X");
        b.setUrl("https://b");

        assertThat(b).as("Same id should imply equality even if URLs differ").isEqualTo(a);
        assertThat(b.hashCode()).as("Equal objects must have equal hashCodes").isEqualTo(a.hashCode());

        WebPageContent c = new WebPageContent();
        c.setUrl("https://same");
        WebPageContent d = new WebPageContent();
        d.setUrl("https://same");
        assertThat(d).as("When ids are null, equality should fall back to URL").isEqualTo(c);
        assertThat(d.hashCode()).isEqualTo(c.hashCode());

        WebPageContent e = new WebPageContent();
        e.setId("E");
        e.setUrl("https://same");
        WebPageContent f = new WebPageContent();
        f.setUrl("https://same");
        assertThat(f).as("If either id is present, equality compares ids only").isNotEqualTo(e);

        WebPageContent g = new WebPageContent();
        g.setId("G1");
        WebPageContent h = new WebPageContent();
        h.setId("G2");
        assertThat(h).as("Different non-null ids should not be equal").isNotEqualTo(g);
    }

    @Test
    void toString_containsKeyFields() {
        WebPageContent doc = new WebPageContent();
        doc.setId("ID");
        doc.setUrl("https://example.com/t");
        doc.setContents(List.of("t"));
        String s = doc.toString();
        assertThat(s).contains("ID");
        assertThat(s).contains("https://example.com/t");
        assertThat(s).contains(doc.getHash());
    }

    @Test
    void defaultConstructor_initialState() {
        WebPageContent doc = new WebPageContent();
        assertThat(doc.getHash()).as("Hash should be null until url/contents are set").isNull();
        assertThat(doc.getContents()).isNull();
        assertThat(doc.getOutLinks()).isNull();
    }

}
