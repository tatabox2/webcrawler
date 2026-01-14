package org.smileyface.webcrawler.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.smileyface.webcrawler.extractor.ClassNameContentRule;
import org.smileyface.webcrawler.extractor.ContentRule;
import org.smileyface.webcrawler.extractor.MinCharacterRule;
import org.smileyface.webcrawler.extractor.TagNameContentRule;

/**
 * Configuration properties for the simple crawler link discovery.
 */
@ConfigurationProperties(prefix = "crawler")
public class CrawlerProperties {

    /**
     * Maximum crawl depth starting from the entry URL. depth=0 means only the entry page.
     */
    private int maxDepth = 1;

    /**
     * List of Java regex patterns; a URL must match at least one include (if provided) to be accepted.
     */
    private List<String> includeUrlPatterns = new ArrayList<>();

    /**
     * List of Java regex patterns; a URL matching any exclude will be rejected.
     */
    private List<String> excludeUrlPatterns = new ArrayList<>();

    /** Optional user agent used when fetching pages. */
    private String userAgent = "SmileyfaceWebCrawler/0.1";

    /** Fetch timeout in milliseconds. */
    private int requestTimeoutMs = 10000;

    /**
     * Namespace/prefix for distributed queue keys (e.g., in Redis). Defaults to "crawler".
     */
    private String queueNamespace = "crawler";

    /** Generic content rules that apply to all pages unless overridden. */
    private ContentRulesConfig contentRules;

    /** Page-specific configurations (URL + optional content rules). */
    private List<PageConfig> pages = new ArrayList<>();

    /** Pre-built map of pageUrl -> content rules, constructed in the constructor. */
    private Map<String, List<ContentRule>> pageRulesMap = new HashMap<>();

    /** Cached generic (global) rules list built from {@link #contentRules}. */
    private List<ContentRule> genericRules = new ArrayList<>();

    /** Optional Elasticsearch index name for storing crawled page contents. */
    private String elasticIndexName;

    /**
     * Loads default values from classpath resource WebCrawlerConfig.json if available.
     * Spring will still bind/override values from application properties as usual.
     */
    public CrawlerProperties() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("WebCrawlerConfig.json")) {
            if (in != null) {
                ObjectMapper mapper = new ObjectMapper();
                WebCrawlerConfig cfg = mapper.readValue(in, WebCrawlerConfig.class);
                // Apply basic crawler fields when present
                if (cfg.maxDepth != null) this.maxDepth = cfg.maxDepth;
                if (cfg.includeUrlPatterns != null) this.includeUrlPatterns = new ArrayList<>(cfg.includeUrlPatterns);
                if (cfg.excludeUrlPatterns != null) this.excludeUrlPatterns = new ArrayList<>(cfg.excludeUrlPatterns);
                if (cfg.userAgent != null && !cfg.userAgent.isBlank()) this.userAgent = cfg.userAgent;
                if (cfg.requestTimeoutMs != null && cfg.requestTimeoutMs > 0) this.requestTimeoutMs = cfg.requestTimeoutMs;
                if (cfg.queueNamespace != null && !cfg.queueNamespace.isBlank()) this.queueNamespace = cfg.queueNamespace;
                if (cfg.elasticIndexName != null && !cfg.elasticIndexName.isBlank()) this.elasticIndexName = cfg.elasticIndexName;
                // Content rules and pages
                this.contentRules = cfg.contentRules;
                if (cfg.pages != null) this.pages = new ArrayList<>(cfg.pages);
            }
        } catch (Exception ignored) {
            // Keep defaults when file missing or malformed; do not fail application startup
        }
        // Build the page rules map once during construction
        rebuildPageRulesMap();
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public List<String> getIncludeUrlPatterns() {
        return includeUrlPatterns;
    }

    public void setIncludeUrlPatterns(List<String> includeUrlPatterns) {
        this.includeUrlPatterns = includeUrlPatterns != null ? includeUrlPatterns : new ArrayList<>();
    }

    public List<String> getExcludeUrlPatterns() {
        return excludeUrlPatterns;
    }

    public void setExcludeUrlPatterns(List<String> excludeUrlPatterns) {
        this.excludeUrlPatterns = excludeUrlPatterns != null ? excludeUrlPatterns : new ArrayList<>();
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public String getQueueNamespace() {
        return queueNamespace;
    }

    public void setQueueNamespace(String queueNamespace) {
        this.queueNamespace = (queueNamespace == null || queueNamespace.isBlank()) ? "crawler" : queueNamespace;
    }

    public ContentRulesConfig getContentRules() {
        return contentRules;
    }

    public void setContentRules(ContentRulesConfig contentRules) {
        this.contentRules = contentRules;
        // Rebuild caches so getters reflect latest rules
        rebuildPageRulesMap();
    }

    public List<PageConfig> getPages() {
        return pages;
    }

    public void setPages(List<PageConfig> pages) {
        this.pages = pages != null ? pages : new ArrayList<>();
        // Rebuild caches so getters reflect latest rules
        rebuildPageRulesMap();
    }

    public String getElasticIndexName() {
        return elasticIndexName;
    }

    public void setElasticIndexName(String elasticIndexName) {
        this.elasticIndexName = (elasticIndexName == null || elasticIndexName.isBlank()) ? null : elasticIndexName;
    }

    /**
     * Build a mapping from page URL to a list of ContentRule, combining generic
     * rules (if any) with page-specific rules.
     */
    private Map<String, List<ContentRule>> getPageRulesMap() {
        return pageRulesMap;
    }

    /**
     * Returns the list of {@link ContentRule} for the given URL.
     * If the URL exactly matches a configured page entry, returns the merged
     * list of generic and page-specific rules for that page. Otherwise, returns
     * the generic rules. Null/blank URLs are treated as unmatched.
     *
     * @param url absolute URL string
     * @return list of rules to apply (never null)
     */
    public List<ContentRule> getContentRules(String url) {
        if (url == null || url.isBlank()) {
            return genericRules;
        }
        List<ContentRule> rules = pageRulesMap.get(url);
        return (rules != null) ? rules : genericRules;
    }

    private List<ContentRule> buildRules(ContentRulesConfig cfg) {
        List<ContentRule> list = new ArrayList<>();
        if (cfg == null) return list;
        if (cfg.minCharacter != null && cfg.minCharacter > 0) {
            list.add(new MinCharacterRule(cfg.minCharacter));
        }
        if (cfg.tagName != null && !cfg.tagName.isBlank()) {
            list.add(new TagNameContentRule(cfg.tagName.trim()));
        }
        if (cfg.classNames != null && !cfg.classNames.isBlank()) {
            String[] parts = cfg.classNames.split(",");
            for (String p : parts) {
                String cls = p.trim();
                if (!cls.isEmpty()) list.add(new ClassNameContentRule(cls));
            }
        }
        return list;
    }

    private void rebuildPageRulesMap() {
        Map<String, List<ContentRule>> map = new HashMap<>();
        // Build and cache the generic rules first
        this.genericRules = buildRules(this.contentRules);
        if (pages != null) {
            for (PageConfig p : pages) {
                if (p == null || p.url == null) continue;
                List<ContentRule> rules = new ArrayList<>(this.genericRules);
                if (p.contentRules != null) {
                    rules.addAll(buildRules(p.contentRules));
                }
                map.put(p.url, rules);
            }
        }
        this.pageRulesMap = map;
    }

    // --------- Nested config DTOs for JSON mapping ---------
    public static class WebCrawlerConfig {
        public Integer maxDepth;
        public List<String> includeUrlPatterns;
        public List<String> excludeUrlPatterns;
        public String userAgent;
        public Integer requestTimeoutMs;
        public String queueNamespace;
        public String elasticIndexName;
        public ContentRulesConfig contentRules;
        public List<PageConfig> pages;
    }

    public static class PageConfig {
        public String url;
        public ContentRulesConfig contentRules;
    }

    public static class ContentRulesConfig {
        public Integer minCharacter;
        public String tagName;
        public String classNames; // comma-separated
    }
}
