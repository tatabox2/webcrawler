package org.smileyface.webcrawler.extractor;

import org.jsoup.nodes.Element;

/**
 * A ContentRule that matches elements by a CSS class name.
 * <p>
 * Matching uses {@link Element#hasClass(String)} which checks for the presence
 * of the class among the element's whitespace-separated {@code class} attribute
 * values. Input class name is validated to be non-null and non-blank and is
 * trimmed; matching is performed exactly as Jsoup defines (case-sensitive).
 */
public final class ClassNameContentRule implements ContentRule {

    private final String className;

    /**
     * Creates a rule that matches elements which have the given CSS class.
     *
     * @param className the CSS class name to match; must not be null or blank
     */
    public ClassNameContentRule(String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("className must not be null/blank");
        }
        this.className = className.trim();
    }

    /**
     * @return the configured CSS class name
     */
    public String getClassName() {
        return className;
    }

    @Override
    public boolean isMatched(Element element) {
        if (element == null) return false;
        return element.hasClass(className);
    }
}
