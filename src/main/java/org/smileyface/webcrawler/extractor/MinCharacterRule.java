package org.smileyface.webcrawler.extractor;

import org.jsoup.nodes.Element;

/**
 * A ContentRule that matches elements whose visible text length (after trimming) is
 * at least a specified minimum number of characters.
 */
public final class MinCharacterRule implements ContentRule {

    private final int minChars;

    /**
     * Creates a rule that matches when the element's trimmed text length is greater than
     * or equal to {@code minChars}. Negative values are treated as zero.
     *
     * @param minChars minimum number of characters required to match
     */
    public MinCharacterRule(int minChars) {
        this.minChars = Math.max(0, minChars);
    }

    /**
     * @return the configured minimum characters threshold
     */
    public int getMinChars() {
        return minChars;
    }

    @Override
    public boolean isMatched(Element element) {
        if (element == null) return false;
        String text = element.text();
        int len = (text == null) ? 0 : text.trim().length();
        return len >= minChars;
    }
}
