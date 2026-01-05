package org.smileyface.webcrawler.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebPageContentTest {

    @Test
    void computeHash_isDeterministicAndSensitiveToInputs() {
        String url = "https://example.com/a";
        String content1 = "hello";
        String content2 = "hello!";

        String h1 = WebPageContent.computeHash(url, content1);
        String h1Again = WebPageContent.computeHash(url, content1);
        String h2 = WebPageContent.computeHash(url, content2);

        assertEquals(h1, h1Again, "Hash should be deterministic for same inputs");
        assertNotEquals(h1, h2, "Hash should change when content changes");
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

        assertEquals(WebPageContent.computeHash(url, content), doc.getHash());
    }

    @Test
    void setters_recomputeHashOnUrlOrContentChange() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/y");
        doc.setContents(List.of("A"));
        String h1 = doc.getHash();
        assertEquals(WebPageContent.computeHash(doc.getUrl(), doc.getContents()), h1);

        doc.setContents(List.of("B"));
        String h2 = doc.getHash();
        assertEquals(WebPageContent.computeHash(doc.getUrl(), doc.getContents()), h2);
        assertNotEquals(h1, h2, "Changing content should change hash");

        doc.setUrl("https://example.com/z");
        String h3 = doc.getHash();
        assertEquals(WebPageContent.computeHash(doc.getUrl(), doc.getContents()), h3);
        assertNotEquals(h2, h3, "Changing URL should change hash");
    }

    @Test
    void computeHash_handlesNullsAsEmptyStrings() {
        // String-based variant
        String a = WebPageContent.computeHash(null, (String) null);
        String b = WebPageContent.computeHash("", "");
        String c = WebPageContent.computeHash("", (String) null);
        String d = WebPageContent.computeHash(null, "");

        assertEquals(a, b);
        assertEquals(b, c);
        assertEquals(c, d);

        // List-based variant should be equivalent when contents are empty or null
        String la = WebPageContent.computeHash(null, (java.util.List<String>) null);
        String lb = WebPageContent.computeHash("", java.util.List.of());
        String lc = WebPageContent.computeHash("", (java.util.List<String>) null);
        String ld = WebPageContent.computeHash(null, java.util.List.of());

        assertEquals(la, lb);
        assertEquals(lb, lc);
        assertEquals(lc, ld);
        // And equal to the string-based empty content hash
        assertEquals(a, la);
    }

    @Test
    void addContents_string_updatesHash() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/hash");
        doc.setContents(List.of("A"));
        String h1 = doc.getHash();

        doc.addContents("B");

        String h2 = doc.getHash();
        assertNotEquals(h1, h2, "Hash should change after adding a content segment");
        assertEquals(WebPageContent.computeHash(doc.getUrl(), doc.getContents()), h2);
    }

    @Test
    void addContents_list_updatesHash_andEmptyIsNoOp() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/hash2");
        doc.setContents(List.of("one"));
        String h1 = doc.getHash();

        doc.addContents(List.of("two", "three"));
        String h2 = doc.getHash();
        assertNotEquals(h1, h2, "Hash should change after adding multiple content segments");
        assertEquals(WebPageContent.computeHash(doc.getUrl(), doc.getContents()), h2);

        // Empty list should be a no-op and not change the hash
        doc.addContents(List.of());
        String h3 = doc.getHash();
        assertEquals(h2, h3, "Adding an empty list should not change the hash");
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
        assertNotEquals(h1, h2, "Hash should change after replacing contents");
        assertEquals(newContents, doc.getContents(), "Contents should be replaced by updateContents(List)");
        assertEquals(WebPageContent.computeHash(doc.getUrl(), doc.getContents()), h2);
    }

    @Test
    void updateContents_index_overridesEntireListAndUpdatesHash() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/override");
        doc.setContents(List.of("old1", "old2"));
        String before = doc.getHash();

        doc.updateContents(5, "only"); // index is ignored by spec

        List<String> expected = List.of("only");
        assertEquals(expected, doc.getContents(), "Contents should be overridden to a single element");
        assertNotEquals(before, doc.getHash(), "Hash should change after overriding contents");
        assertEquals(WebPageContent.computeHash(doc.getUrl(), expected), doc.getHash());
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

        assertEquals("id-123", doc.getId());
        assertEquals("https://example.com/", doc.getUrl());
        assertEquals("example.com", doc.getDomain());
        assertEquals(now, doc.getCrawlTimestamp());
        assertEquals(CrawlStatus.OK, doc.getStatus());
        assertEquals(200, doc.getHttpStatus());
        assertEquals(321L, doc.getFetchDurationMs());
        assertEquals(2, doc.getCrawlDepth());
        assertEquals("Hello", doc.getTitle());
        assertEquals("Desc", doc.getDescription());
        assertEquals(42, doc.getContentLength());
        assertEquals("text/html", doc.getContentType());
        assertEquals("en", doc.getLanguage());

        // Defensive copy on setContents
        java.util.ArrayList<String> contentsSrc = new java.util.ArrayList<>(List.of("a", "b"));
        doc.setContents(contentsSrc);
        contentsSrc.add("mutate");
        assertEquals(List.of("a", "b"), doc.getContents(), "Internal contents should not reflect external list mutations");

        // Defensive copy on setOutLinks
        java.util.ArrayList<String> outLinksSrc = new java.util.ArrayList<>(List.of("l1", "l2"));
        doc.setOutLinks(outLinksSrc);
        outLinksSrc.add("l3");
        assertEquals(List.of("l1", "l2"), doc.getOutLinks(), "Internal outLinks should not reflect external list mutations");
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

        assertEquals(List.of("x", "y"), doc.getContents(), "Constructor must defensively copy contents");
        assertEquals(List.of("a", "b"), doc.getOutLinks(), "Constructor must defensively copy outLinks");
        assertEquals(WebPageContent.computeHash(url, List.of("x", "y")), doc.getHash(), "Hash must be derived from url+contents");
    }

    @Test
    void computeHash_listOrderAndSegmentSeparationMatters() {
        String url = "u";
        String h1 = WebPageContent.computeHash(url, List.of("a", "b"));
        String h2 = WebPageContent.computeHash(url, List.of("b", "a"));
        assertNotEquals(h1, h2, "Order of segments should affect the hash");

        String hx = WebPageContent.computeHash(url, List.of("ab", "c"));
        String hy = WebPageContent.computeHash(url, List.of("a", "bc"));
        assertNotEquals(hx, hy, "Segment separator must avoid concatenation collisions");
    }

    @Test
    void addContents_null_isStoredAndHashUpdated() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/null");
        doc.setContents(List.of("x"));
        String before = doc.getHash();

        doc.addContents((String) null);

        assertEquals(2, doc.getContents().size());
        assertNull(doc.getContents().get(1));
        assertNotEquals(before, doc.getHash());
        assertEquals(WebPageContent.computeHash(doc.getUrl(), java.util.Arrays.asList("x", null)), doc.getHash());
    }

    @Test
    void setContents_nullClearsContentsAndRecomputesHash() {
        WebPageContent doc = new WebPageContent();
        doc.setUrl("https://example.com/clear");
        doc.setContents(List.of("something"));
        String expected = WebPageContent.computeHash(doc.getUrl(), (List<String>) null);

        doc.setContents(null);

        assertNull(doc.getContents(), "Contents should become null");
        assertEquals(expected, doc.getHash(), "Hash should be recomputed with null contents");
    }

    @Test
    void setOutLinks_nullAllowed() {
        WebPageContent doc = new WebPageContent();
        doc.setOutLinks(null);
        assertNull(doc.getOutLinks());
    }

    @Test
    void equalsAndHashCode_semantics() {
        WebPageContent a = new WebPageContent();
        a.setId("X");
        a.setUrl("https://a");

        WebPageContent b = new WebPageContent();
        b.setId("X");
        b.setUrl("https://b");

        assertEquals(a, b, "Same id should imply equality even if URLs differ");
        assertEquals(a.hashCode(), b.hashCode(), "Equal objects must have equal hashCodes");

        WebPageContent c = new WebPageContent();
        c.setUrl("https://same");
        WebPageContent d = new WebPageContent();
        d.setUrl("https://same");
        assertEquals(c, d, "When ids are null, equality should fall back to URL");
        assertEquals(c.hashCode(), d.hashCode());

        WebPageContent e = new WebPageContent();
        e.setId("E");
        e.setUrl("https://same");
        WebPageContent f = new WebPageContent();
        f.setUrl("https://same");
        assertNotEquals(e, f, "If either id is present, equality compares ids only");

        WebPageContent g = new WebPageContent();
        g.setId("G1");
        WebPageContent h = new WebPageContent();
        h.setId("G2");
        assertNotEquals(g, h, "Different non-null ids should not be equal");
    }

    @Test
    void toString_containsKeyFields() {
        WebPageContent doc = new WebPageContent();
        doc.setId("ID");
        doc.setUrl("https://example.com/t");
        doc.setContents(List.of("t"));
        String s = doc.toString();
        assertTrue(s.contains("ID"));
        assertTrue(s.contains("https://example.com/t"));
        assertTrue(s.contains(doc.getHash()));
    }

    @Test
    void defaultConstructor_initialState() {
        WebPageContent doc = new WebPageContent();
        assertNull(doc.getHash(), "Hash should be null until url/contents are set");
        assertNull(doc.getContents());
        assertNull(doc.getOutLinks());
    }

}
