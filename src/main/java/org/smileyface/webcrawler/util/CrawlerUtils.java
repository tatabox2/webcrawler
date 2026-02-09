package org.smileyface.webcrawler.util;

import org.smileyface.webcrawler.crawler.CrawlerProperties;
import org.smileyface.webcrawler.elasticsearch.ElasticContext;

public class CrawlerUtils {

    private CrawlerUtils() {
        // No instanciation
    }

    // String input and remove html tag
    public static String removeHtmlTags(String input) {
        if (input == null) {
            return null;
        }
        return input.replaceAll("<[^>]*>", "");
    }

    /**
     * Builds the Elasticsearch index name by concatenating the crawler indexPrefix and the tenantId
     * from the ElasticContext with a dash in between: prefix + "-" + tenantId.
     *
     * If the prefix is null/blank, returns null to signal "do not index".
     * If the context is null or has a blank tenant id, "default" is used as the tenant id.
     */
    public static String getIndexName(CrawlerProperties props, ElasticContext ctx) {
        if (props == null) return null;
        String prefix = props.getIndexPrefix();
        if (prefix == null || prefix.isBlank()) return null;
        String tenant = (ctx == null || ctx.getTenantId() == null || ctx.getTenantId().isBlank())
                ? "default"
                : ctx.getTenantId();
        return prefix + "-" + tenant;
    }


}
