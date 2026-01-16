package org.smileyface.webcrawler.processor;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smileyface.webcrawler.crawler.CrawlerProperties;
import org.smileyface.webcrawler.crawler.LinkQueue;
import org.smileyface.webcrawler.elasticsearch.ElasticContext;
import org.smileyface.webcrawler.elasticsearch.ElasticRestClient;
import org.smileyface.webcrawler.model.CrawlStatus;
import org.smileyface.webcrawler.model.WebPageContent;

import java.net.URI;
import java.time.Instant;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A thread-safe processor that consumes URLs from a LinkQueue and produces WebPageContent
 * using Jsoup HTML parsing. The processor stops when the queue is empty or when stop() is requested.
 */
public class WebPageProcessor implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(WebPageProcessor.class);

    private final String id;
    private final LinkQueue linkQueue;
    private final CrawlerProperties properties;
    private final Consumer<WebPageContent> sink;
    private final ElasticContext elasticContext;
    private final ElasticRestClient elasticRestClient; // optional

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicLong processedCount = new AtomicLong(0);

    private volatile ProcessorState state = ProcessorState.NEW;
    private volatile String lastUrl;
    private volatile String lastError;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;

    public WebPageProcessor(String id,
                             LinkQueue linkQueue,
                             CrawlerProperties properties,
                             Consumer<WebPageContent> sink,
                             ElasticContext elasticContext) {
        this.id = Objects.requireNonNull(id, "id");
        this.linkQueue = Objects.requireNonNull(linkQueue, "linkQueue");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.elasticContext = elasticContext;
        this.elasticRestClient = (elasticContext == null) ? null : new ElasticRestClient(elasticContext);
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

    public void stop() {
        stopRequested.set(true);
        if (state == ProcessorState.NEW) {
            transitionTo(ProcessorState.STOPPED, null);
        }
    }

    public ProcessorStatus getStatus() {
        return new ProcessorStatus(id, state, processedCount.get(), lastUrl, lastError, startedAt, finishedAt);
    }

    @Override
    public void run() {
        transitionTo(ProcessorState.RUNNING, null);
        try {
            for (;;) {
                if (stopRequested.get()) {
                    transitionTo(ProcessorState.STOPPED, null);
                    return;
                }
                String url = linkQueue.deQueue();
                if (url == null) {
                    // queue empty -> stop
                    transitionTo(ProcessorState.COMPLETED, null);
                    return;
                }
                lastUrl = url;
                processUrl(url);
                processedCount.incrementAndGet();
            }
        } catch (Throwable t) {
            lastError = t.getMessage();
            transitionTo(ProcessorState.ERROR, t);
        }
    }

    /**
     * Centralized state transition with structured logging. Ensures timestamps are set
     * and duration is included for terminal states (STOPPED/COMPLETED/ERROR).
     */
    private void transitionTo(ProcessorState newState, Throwable error) {
        ProcessorState old = this.state;
        if (newState == ProcessorState.RUNNING) {
            if (this.startedAt == null) {
                this.startedAt = Instant.now();
            }
            this.state = ProcessorState.RUNNING;
            log.info("Processor {} state {} -> {} (startedAt={})", id, old, this.state, startedAt);
            return;
        }

        if (newState == ProcessorState.STOPPED || newState == ProcessorState.COMPLETED || newState == ProcessorState.ERROR) {
            this.finishedAt = Instant.now();
            this.state = newState;
            long dur = startedAt != null ? durationMs(startedAt, finishedAt) : 0L;
            long count = processedCount.get();
            switch (newState) {
                case STOPPED -> log.info("Processor {} state {} -> STOPPED after {} ms (processed={}, lastUrl={})", id, old, dur, count, lastUrl);
                case COMPLETED -> log.info("Processor {} state {} -> COMPLETED after {} ms (processed={})", id, old, dur, count);
                case ERROR -> {
                    String msg = lastError != null ? lastError : (error != null ? error.getMessage() : null);
                    if (error != null) {
                        log.error("Processor {} state {} -> ERROR after {} ms (processed={}, lastUrl={}, error={})", id, old, dur, count, lastUrl, msg, error);
                    } else {
                        log.error("Processor {} state {} -> ERROR after {} ms (processed={}, lastUrl={}, error={})", id, old, dur, count, lastUrl, msg);
                    }
                }
                default -> {}
            }
            return;
        }

        // Fallback for any other transitions (currently none besides NEW -> something)
        this.state = newState;
        log.info("Processor {} state {} -> {}", id, old, newState);
    }

    private void processUrl(String url) throws Exception {
        Instant start = Instant.now();
        int httpStatus = -1;
        String contentType = null;
        Document doc = null;
        try {
            Connection conn = Jsoup.connect(url)
                    .userAgent(Objects.toString(properties.getUserAgent(), "SmileyfaceWebCrawler/0.1"))
                    .timeout(Math.max(0, properties.getRequestTimeoutMs()))
                    .followRedirects(true)
                    .ignoreHttpErrors(true);

            Connection.Response res = conn.execute();
            httpStatus = res.statusCode();
            contentType = res.contentType();
            doc = res.parse();
        } catch (Exception e) {
            // Build an error WebPageContent
            WebPageContent w = new WebPageContent();
            w.setUrl(url);
            w.setDomain(domainOf(url));
            w.setCrawlTimestamp(System.currentTimeMillis());
            w.setStatus(CrawlStatus.ERROR_FETCH);
            w.setHttpStatus(httpStatus > 0 ? httpStatus : null);
            w.setContentType(contentType);
            w.setFetchDurationMs(durationMs(start, Instant.now()));
            sink.accept(w);
            return;
        }

        try {
            String title = doc.title();
            String text = doc.body() != null ? doc.body().text() : "";

            WebPageContent w = new WebPageContent();
            w.setUrl(url);
            w.setDomain(domainOf(url));
            w.setCrawlTimestamp(System.currentTimeMillis());
            w.setStatus(CrawlStatus.OK);
            w.setHttpStatus(httpStatus);
            w.setContentType(contentType);
            w.setTitle(title);
            // store as single segment contents
            w.setContents(java.util.List.of(text));
            w.setContentLength(text != null ? text.length() : 0);
            w.setFetchDurationMs(durationMs(start, Instant.now()));
            sink.accept(w);
            // Index the document into Elasticsearch if configured and only on successful population
            String indexName = getIndexName(properties, elasticContext);
            if (elasticRestClient != null && indexName != null && !indexName.isBlank()) {
                try {
                    String assignedId = elasticRestClient.indexDocument(indexName, w);
                    if (assignedId != null && (w.getId() == null || w.getId().isBlank())) {
                        w.setId(assignedId);
                    }
                } catch (IOException e) {
                    String msg = "Processor %s failed to index document for url=%s to index=%s - %s".formatted(id, url, indexName, e.getMessage());
                    log.error(msg, e.getMessage());
                    throw new IOException(msg, e);
                } catch (Exception e) {
                    String msg = "Processor %s unexpected error indexing document for url=%s to index=%s".formatted(id, url, indexName);
                    log.error(msg, e);
                    throw new Exception(msg, e);
                }
            }
        } catch (Exception e) {
            WebPageContent w = new WebPageContent();
            w.setUrl(url);
            w.setDomain(domainOf(url));
            w.setCrawlTimestamp(System.currentTimeMillis());
            w.setStatus(CrawlStatus.ERROR_PARSE);
            w.setHttpStatus(httpStatus);
            w.setContentType(contentType);
            w.setFetchDurationMs(durationMs(start, Instant.now()));
            sink.accept(w);
            throw new Exception(e);
        }
    }

    private static String domainOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static long durationMs(Instant start, Instant end) {
        return Math.max(0, end.toEpochMilli() - start.toEpochMilli());
    }
}
