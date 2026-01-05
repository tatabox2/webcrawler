package org.smileyface.webcrawler.util;

public class CrawlerUtils {

    private CrawlerUtils() {
        // No instanciation
    };

    // String input and remove html tag
    public static String removeHtmlTags(String input) {
        if (input == null) {
            return null;
        }
        return input.replaceAll("<[^>]*>", "");
    }

}
