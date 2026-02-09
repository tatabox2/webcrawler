package org.smileyface.webcrawler.service;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smileyface.webcrawler.crawler.CrawlerProperties;
import org.smileyface.webcrawler.crawler.LinkQueue;
import org.smileyface.webcrawler.elasticsearch.ElasticContext;
import org.smileyface.webcrawler.processor.ProcessorManager;
import org.smileyface.webcrawler.model.WebPageContent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * A simple crawler that starts from one entry URL, traverses HTML links up to configured depth,
 * filters by include/exclude patterns, and enqueues discovered subpage links into a LinkQueue
 * for later processing.
 */
@Service
public class CrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);

    private final LinkQueue linkQueue;
    private final CrawlerProperties properties;
    private ProcessorManager processorManager; // optional when created manually in tests
    private ElasticContext elasticContext;     // instantiated from application.yml when using Spring
    
    // Inject explicit worker count from application properties if provided.
    @Value("${crawler.workerCount:15}")
    private int workerCount;

    public CrawlerService(LinkQueue linkQueue, CrawlerProperties properties) {
        this.linkQueue = linkQueue;
        this.properties = properties;
    }

    /**
     * Spring-enabled constructor: autowires ProcessorManager and injects ElasticContext bean.
     */
    @Autowired
    public CrawlerService(LinkQueue linkQueue,
                          CrawlerProperties properties,
                          ProcessorManager processorManager,
                          ElasticContext elasticContext) {
        this(linkQueue, properties);
        this.processorManager = processorManager;
        this.elasticContext = elasticContext;
    }

    /**
     * Expose the autowired ProcessorManager (may be null when constructed in tests).
     */
    public ProcessorManager getProcessorManager() { return processorManager; }

    /**
     * Expose the instantiated ElasticContext (may be null when constructed in tests).
     */
    public ElasticContext getElasticContext() { return elasticContext; }

    /**
     * Crawl starting from the given entry URL. Discovered subpage links that pass filters are
     * pushed into the LinkQueue. Traversal is limited by crawler.maxDepth.
     *
     * @param entryUrl the starting URL (http/https)
     */
    public void crawl(String entryUrl) {
        crawl(entryUrl, false);
    }

    /**
     * Crawl starting from the given entry URL with an option to block until completion.
     *
     * @param entryUrl the starting URL (http/https)
     * @param waitForCompletion if true, blocks until all links have been processed
     */
    public void crawl(String entryUrl, boolean waitForCompletion) {
        String start = normalizeUrl(entryUrl);
        if (start == null) {
            log.warn("Invalid entry URL: {}", entryUrl);
            return;
        }

        int maxDepth = Math.max(0, properties.getMaxDepth());
        List<Pattern> includes = compilePatterns(properties.getIncludeUrlPatterns());
        List<Pattern> excludes = compilePatterns(properties.getExcludeUrlPatterns());

        Deque<UrlDepth> frontier = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        frontier.add(new UrlDepth(start, 0));
        visited.add(start);
        // Only enqueue the entry URL when a ProcessorManager is present (i.e., when we intend
        // to actually process the page content). In link-discovery-only contexts (tests that
        // construct CrawlerService without a ProcessorManager), we do not enqueue the entry URL
        // so that the queue reflects only discovered subpage links.
        if (processorManager != null) {
            linkQueue.enqueue(start);
        }

        while (!frontier.isEmpty()) {
            UrlDepth current = frontier.poll();
            if (current.depth > maxDepth) {
                continue;
            }

            Document doc = fetch(current.url);
            if (doc == null) continue;

            if (current.depth == maxDepth) {
                // At max depth: don't traverse further, but we still examine and enqueue subpage links
                enqueueFilteredLinks(doc, includes, excludes);
                continue;
            }

            Elements links = doc.select("a[href]");
            for (Element a : links) {
                String abs = a.attr("abs:href");
                String normalized = normalizeUrl(abs);
                if (normalized == null) continue;
                if (!isAcceptedByFilters(normalized, includes, excludes)) continue;

                // Always enqueue accepted subpage links for later processing
                linkQueue.enqueue(normalized);

                // Continue traversal if not yet visited and within depth limit
                if (visited.add(normalized)) {
                    frontier.add(new UrlDepth(normalized, current.depth + 1));
                }
            }
        }

        // After enqueuing links, optionally start processors to consume the queue
        if (processorManager != null) {
            if (processorManager.isRunning()) {
                log.debug("ProcessorManager is already running; skip starting from crawl()");
            } else {
                int workers = workerCount;
                Consumer<WebPageContent> sink = content -> {
                    if (content != null) {
                        log.debug("Sink consumed page: url={}, status={}", content.getUrl(), content.getStatus());
                    }
                };
                processorManager.start(workers, linkQueue, properties, sink);
                log.debug("ProcessorManager started with workers={}, waitForCompletion={}", workers, waitForCompletion);
                if (waitForCompletion) {
                    processorManager.awaitAll(null); // wait indefinitely; awaitAll handles shutdown/state
                }
            }
        }
    }

    private void enqueueFilteredLinks(Document doc, List<Pattern> includes, List<Pattern> excludes) {
        Elements links = doc.select("a[href]");
        for (Element a : links) {
            String abs = a.attr("abs:href");
            String normalized = normalizeUrl(abs);
            if (normalized == null) continue;
            if (!isAcceptedByFilters(normalized, includes, excludes)) continue;
            linkQueue.enqueue(normalized);
        }
    }

    private Document fetch(String url) {
        try {
            Connection conn = Jsoup.connect(url)
                    .userAgent(Objects.toString(properties.getUserAgent(), "SmileyfaceWebCrawler/0.1"))
                    .timeout(Math.max(0, properties.getRequestTimeoutMs()))
                    .followRedirects(true)
                    .ignoreHttpErrors(true);
            // We prefer HTML; Jsoup will parse the body as HTML if possible
            return conn.get();
        } catch (Exception e) {
            log.debug("Failed to fetch {}: {}", url, e.getMessage());
            return null;
        }
    }

    private List<Pattern> compilePatterns(List<String> raw) {
        List<Pattern> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            try {
                out.add(Pattern.compile(s));
            } catch (Exception e) {
                log.warn("Invalid regex pattern in crawler config: {} (ignored)", s);
            }
        }
        return out;
    }

    private boolean isAcceptedByFilters(String url, List<Pattern> includes, List<Pattern> excludes) {
        // Excludes take precedence
        for (Pattern p : excludes) {
            if (p.matcher(url).find()) return false;
        }
        if (includes.isEmpty()) return true; // no includes means accept all (subject to excludes)
        for (Pattern p : includes) {
            if (p.matcher(url).find()) return true;
        }
        return false;
    }

    private String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            String lowerScheme = scheme.toLowerCase();
            if (!lowerScheme.equals("http") && !lowerScheme.equals("https")) {
                return null; // skip non-http(s)
            }
            // Remove fragment
            uri = new URI(
                    lowerScheme,
                    uri.getUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    null
            );
            // Normalize: remove default port and resolve empty path to "/"
            String host = uri.getHost();
            if (host == null) return null;
            String path = uri.getPath();
            if (path == null || path.isBlank()) path = "/";
            String query = uri.getQuery();

            StringBuilder sb = new StringBuilder();
            sb.append(lowerScheme).append("://").append(host.toLowerCase());
            if (uri.getPort() != -1 && uri.getPort() != defaultPort(lowerScheme)) {
                sb.append(":").append(uri.getPort());
            }
            sb.append(path);
            if (query != null && !query.isBlank()) sb.append('?').append(query);
            return sb.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    private record UrlDepth(String url, int depth) {}
}
