package org.smileyface.webcrawler.extractor;

import org.jsoup.nodes.Element;

/**
 * A rule used by ContentExtractor to decide whether a given HTML element
 * matches and should have its textual content extracted.
 */
@FunctionalInterface
public interface ContentRule {
    /**
     * Returns true if the provided element matches this rule.
     *
     * @param element a non-null Jsoup Element from the parsed HTML document
     * @return true if matched
     */
    boolean isMatched(Element element);
}
