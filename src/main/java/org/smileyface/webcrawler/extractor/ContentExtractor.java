package org.smileyface.webcrawler.extractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Utility to extract textual content segments from an HTML string by applying a set of {@link ContentRule}s.
 */
public final class ContentExtractor {

    /**
     * Parses the provided HTML and extracts text from elements that match any of the provided rules.
     * Traversal is depth-first and preserves document order. When an element matches a rule, its text is
     * added as a single segment and its children are not traversed to avoid duplicate nested captures.
     *
     * @param html  the HTML content string (may be null/blank)
     * @param rules the set of rules to apply (may be null/empty). If null or empty, returns an empty list.
     * @return a list of extracted text segments (possibly empty), in document order
     */
    public List<String> extractContent(String html, Collection<ContentRule> rules) {
        // Backward-compatibility: treat provided rules as match-any and no match-all constraints
        return extractContent(html, rules, null);
    }

    /**
     * Parses the provided HTML and extracts text from elements that satisfy either of the following:
     * - Match ANY of the rules in {@code matchAnyRules}; OR
     * - Match ALL of the rules in {@code matchAllRules}.
     * If both rule sets are provided, the union (logical OR) is used. If both are null/empty, no content is extracted.
     * Traversal is depth-first and preserves document order. When an element matches, its children are skipped.
     *
     * @param html           the HTML content string (may be null/blank)
     * @param matchAnyRules  rules where matching any one is sufficient (may be null/empty)
     * @param matchAllRules  rules where matching all is required (may be null/empty)
     * @return extracted text segments in document order (possibly empty)
     */
    public List<String> extractContent(String html,
                                       Collection<ContentRule> matchAnyRules,
                                       Collection<ContentRule> matchAllRules) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        boolean anyEmpty = (matchAnyRules == null || matchAnyRules.isEmpty());
        boolean allEmpty = (matchAllRules == null || matchAllRules.isEmpty());
        if (anyEmpty && allEmpty) {
            return List.of();
        }

        Document doc = Jsoup.parse(html);
        Element root = doc.body() != null ? doc.body() : doc;
        List<String> out = new ArrayList<>();
        traverse(root, matchAnyRules, matchAllRules, out, false);
        return out;
    }

    private void traverse(Element el,
                          Collection<ContentRule> matchAnyRules,
                          Collection<ContentRule> matchAllRules,
                          List<String> out,
                          boolean parentMatched) {
        boolean matched = !parentMatched && (matchesAny(el, matchAnyRules) || matchesAll(el, matchAllRules));
        if (matched) {
            String text = el.text();
            if (text != null && !text.isBlank()) {
                out.add(text.trim());
            }
            return; // skip children to avoid nested duplicates
        }
        for (Element child : el.children()) {
            traverse(child, matchAnyRules, matchAllRules, out, false);
        }
    }

    private boolean matchesAny(Element el, Collection<ContentRule> rules) {
        if (rules == null || rules.isEmpty()) return false;
        for (ContentRule r : rules) {
            if (r != null && r.isMatched(el)) return true;
        }
        return false;
    }

    private boolean matchesAll(Element el, Collection<ContentRule> rules) {
        if (rules == null || rules.isEmpty()) return false;
        for (ContentRule r : rules) {
            if (r == null) return false; // null rule cannot be matched
            if (!r.isMatched(el)) return false;
        }
        return true;
    }
}
