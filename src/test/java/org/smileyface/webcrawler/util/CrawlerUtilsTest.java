package org.smileyface.webcrawler.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrawlerUtilsTest {

    /**
     * Test case for removing HTML tags from a string with multiple HTML elements.
     */
    @Test
    void testRemoveHtmlTagsWithHtmlTags() {
        String input = "<p>This is <b>bold</b> and <i>italic</i> text.</p>";
        String expected = "This is bold and italic text.";
        String result = CrawlerUtils.removeHtmlTags(input);
        assertEquals(expected, result);
    }

    /**
     * Test case for a string with no HTML tags.
     */
    @Test
    void testRemoveHtmlTagsWithoutHtmlTags() {
        String input = "This is plain text.";
        String expected = "This is plain text.";
        String result = CrawlerUtils.removeHtmlTags(input);
        assertEquals(expected, result);
    }

    /**
     * Test case for an empty string.
     */
    @Test
    void testRemoveHtmlTagsWithEmptyString() {
        String input = "";
        String expected = "";
        String result = CrawlerUtils.removeHtmlTags(input);
        assertEquals(expected, result);
    }

    /**
     * Test case for null input.
     * Since the method does not handle null, this will ensure the behavior is understood.
     */
    @Test
    void testRemoveHtmlTagsWithNullInput() {
        String input = null;
        String result = CrawlerUtils.removeHtmlTags(input);
        assertEquals(null, result);
    }

    /**
     * Test case for a string with only HTML tags.
     */
    @Test
    void testRemoveHtmlTagsWithOnlyHtmlTags() {
        String input = "<div><span></span></div>";
        String expected = "";
        String result = CrawlerUtils.removeHtmlTags(input);
        assertEquals(expected, result);
    }

    /**
     * Test case for a string with nested HTML tags.
     */
    @Test
    void testRemoveHtmlTagsWithNestedHtmlTags() {
        String input = "<div><p>Nested <span>tag</span> example.</p></div>";
        String expected = "Nested tag example.";
        String result = CrawlerUtils.removeHtmlTags(input);
        assertEquals(expected, result);
    }

    /**
     * Test case for a string with special characters and HTML tags.
     */
    @Test
    void testRemoveHtmlTagsWithSpecialCharacters() {
        String input = "<p>Special characters: &amp; &lt; &gt;</p>";
        String expected = "Special characters: &amp; &lt; &gt;";
        String result = CrawlerUtils.removeHtmlTags(input);
        assertEquals(expected, result);
    }

    /**
     * Test case for a string with broken or incomplete HTML tags.
     */
    @Test
    void testRemoveHtmlTagsWithBrokenHtmlTags() {
        String input = "Text with <b>unclosed tags or <i>incorrect nesting</b>";
        String expected = "Text with unclosed tags or incorrect nesting";
        String result = CrawlerUtils.removeHtmlTags(input);
        assertEquals(expected, result);
    }

}