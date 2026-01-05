package org.smileyface.webcrawler.extractor;

import org.jsoup.nodes.Element;

/**
 * A ContentRule that matches elements by their tag name.
 * Matching is case-insensitive and null-safe.
 */
public final class TagNameContentRule implements ContentRule {

    private final String tagName;

    /**
    * Creates a rule that matches elements whose tag name equals the provided value
    * (case-insensitive).
    *
    * @param tagName the HTML tag name to match (e.g., "p", "h1"). Must not be null/blank.
    */
    public TagNameContentRule(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            throw new IllegalArgumentException("tagName must not be null/blank");
        }
        this.tagName = tagName.trim();
    }

    /**
     * @return the configured tag name (as provided, trimmed)
     */
    public String getTagName() {
        return tagName;
    }

    @Override
    public boolean isMatched(Element element) {
        if (element == null) return false;
        return element.tagName().equalsIgnoreCase(tagName);
    }
}
