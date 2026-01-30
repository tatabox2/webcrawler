package org.smileyface.webcrawler.crawler;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    Logger log = LogManager.getLogger(CrawlerProperties.class);

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

    /** Optional index prefix for storing crawled page contents in Elasticsearch. */
    private String indexPrefix;

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
                // Load new field 'indexPrefix'
                if (cfg.indexPrefix != null && !cfg.indexPrefix.isBlank()) {
                    this.indexPrefix = cfg.indexPrefix;
                }
                // Content rules and pages
                this.contentRules = cfg.contentRules;
                if (cfg.pages != null) this.pages = new ArrayList<>(cfg.pages);
            }
        } catch (Exception ignored) {
            log.error("Failed to load default crawler configuration from classpath resource WebCrawlerConfig.json", ignored);
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

    public String getIndexPrefix() {
        return indexPrefix;
    }

    public void setIndexPrefix(String indexPrefix) {
        this.indexPrefix = (indexPrefix == null || indexPrefix.isBlank()) ? null : indexPrefix;
    }

    /**
     * Build a mapping from page URL to a list of ContentRule, combining generic
     * rules (if any) with page-specific rules.
     */
    private Map<String, List<ContentRule>> getPageRulesMap() {
        return pageRulesMap;
    }

    /**
     *  get the matchAll flag value by the passing url pattern
     *  return true;
     */
    public boolean matchAllByUrl(String url) {
        if (url == null || url.isBlank()) return false;
        if (pages == null) return false;

        // loop thorught all pages to find the matchAll flag
        return pages.stream().anyMatch(page -> url.matches(page.urlPattern) && page.matchAll);
    }

    /**
     * Returns the list of {@link ContentRule} for the given URL.
     * This uses Java regular expressions stored in {@link PageConfig#urlPattern} as patterns.
     * If the provided URL matches (via {@code String#matches(String)}) any configured
     * page pattern, the merged list of generic and page-specific rules for the first
     * matching page (in declaration order) is returned. If none match, the generic
     * rules are returned. Null/blank URLs are treated as unmatched.
     *
     * Note: Invalid regex patterns in configuration are ignored gracefully.
     *
     * @param url absolute URL string
     * @return list of rules to apply (never null)
     */
    public List<ContentRule> getContentRules(String url) {
        if (url == null || url.isBlank()) {
            return genericRules;
        }
        if (pages != null) {
            for (PageConfig p : pages) {
                if (p == null || p.urlPattern == null || p.urlPattern.isBlank()) continue;
                try {
                    if (url.matches(p.urlPattern)) {
                        List<ContentRule> rules = pageRulesMap.get(p.urlPattern);
                        if (rules != null) return rules;
                    }
                } catch (java.util.regex.PatternSyntaxException ignored) {
                    // Skip invalid pattern
                }
            }
        }
        return genericRules;
    }

    /**
     * Adds a single {@link PageConfig} at runtime and updates the internal page rules map.
     * If the provided config or its URL is null/blank, the call is ignored.
     *
     * This method merges the currently effective generic rules with the rules defined in the
     * provided {@code pageConfig} and stores the result into {@link #pageRulesMap} under the
     * page URL. If a config for the same URL already exists, it will be overwritten.
     *
     * Note: This does not rebuild the entire map — only the given page entry is (re)computed.
     *
     * @param pageConfig page configuration to add
     */
    public void addPageConfig(PageConfig pageConfig) {
        if (pageConfig == null) return;
        String url = pageConfig.urlPattern;
        if (url == null || url.isBlank()) return;

        if (this.pages == null) {
            this.pages = new ArrayList<>();
        }
        this.pages.add(pageConfig);

        // Build rules for this page by merging current generic rules and page-specific rules
        List<ContentRule> rules = new ArrayList<>();
        if (pageConfig.contentRules != null) {
            rules.addAll(buildRules(pageConfig.contentRules));
        }
        this.pageRulesMap.put(url, rules);
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
        Map<String, Boolean> matchAllMap = new HashMap<>();
        // Build and cache the generic rules first
        this.genericRules = buildRules(this.contentRules);
        if (pages != null) {
            for (PageConfig p : pages) {
                if (p == null || p.urlPattern == null) continue;
                List<ContentRule> rules = new ArrayList<>();
                if (p.contentRules != null) {
                    rules.addAll(buildRules(p.contentRules));
                }
                map.put(p.urlPattern, rules);
                matchAllMap.put(p.urlPattern, p.matchAll);
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
        // Preferred field name for ES index prefix
        public String indexPrefix;
        public ContentRulesConfig contentRules;
        public List<PageConfig> pages;
    }

    public static class PageConfig {

        public PageConfig() {} // for JSON mapping
        public PageConfig(String urlPattern, ContentRulesConfig contentRules) {
            this.urlPattern = urlPattern;
            this.contentRules = contentRules;
        }
        public PageConfig(String urlPattern, boolean matchAll, ContentRulesConfig contentRules) {
            this.urlPattern = urlPattern;
            this.matchAll = matchAll;
            this.contentRules = contentRules;
        }
        public String urlPattern;
        /**
         * When true, indicates that content extraction rules for this page should be treated as a
         * "match all rules" set. Default is false. The actual extraction behavior may consult this
         * flag where applicable.
         */
        public boolean matchAll = false;
        public ContentRulesConfig contentRules;
    }

    public static class ContentRulesConfig {

        public ContentRulesConfig() {} // for JSON mapping
        public ContentRulesConfig(Integer minCharacter, String tagName, String classNames) {
            this.minCharacter = minCharacter;
            this.tagName = tagName;
            this.classNames = classNames;
        }
        public Integer minCharacter;
        public String tagName;
        public String classNames; // comma-separated
    }
}
