package org.smileyface.webcrawler.extractor;

import org.jsoup.nodes.Element;

/**
 * A ContentRule that matches elements based on their inline {@code style} attribute.
 * Matching is performed using a case-insensitive substring check against the full
 * style string (after trimming). This keeps the rule flexible for patterns like
 * "display:none", "color: red", or partial fragments such as "font-weight".
 */
public final class ElementStyleRule implements ContentRule {

    private final String styleFragment;
    private final String styleFragmentLower;

    /**
     * Creates a rule that matches when an element's inline style contains the given fragment
     * (case-insensitive).
     *
     * @param styleFragment non-null, non-blank substring to search for within {@code style}
     */
    public ElementStyleRule(String styleFragment) {
        if (styleFragment == null || styleFragment.isBlank()) {
            throw new IllegalArgumentException("styleFragment must not be null/blank");
        }
        String trimmed = styleFragment.trim();
        this.styleFragment = trimmed;
        this.styleFragmentLower = trimmed.toLowerCase();
    }

    /**
     * @return the configured style fragment
     */
    public String getStyleFragment() {
        return styleFragment;
    }

    @Override
    public boolean isMatched(Element element) {
        if (element == null) return false;
        String style = element.attr("style");
        if (style.isBlank()) return false;
        return style.toLowerCase().contains(styleFragmentLower);
    }
}
