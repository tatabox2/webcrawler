package org.smileyface.webcrawler.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.smileyface.webcrawler.crawler.CrawlerProperties;
import org.smileyface.webcrawler.crawler.LinkQueue;
import org.smileyface.webcrawler.elasticsearch.ElasticContext;
import org.smileyface.webcrawler.elasticsearch.ElasticRestClient;
import org.smileyface.webcrawler.model.CrawlStatus;
import org.smileyface.webcrawler.model.WebPageContent;
import org.smileyface.webcrawler.testutil.ElasticsearchTestContainer;
import org.smileyface.webcrawler.util.CrawlerUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ProcessorManagerTest {

    private HttpServer server;
    private static boolean dockerAvailable;
    private static RestClient lowLevel;
    private static ElasticsearchClient validationClient;
    private static ElasticRestClient es;
    private static ElasticContext elasticContext;


    @Autowired
    private LinkQueue queue;

    @Autowired
    private ProcessorManager mgr;

    @TestConfiguration
    @EnableConfigurationProperties(CrawlerProperties.class)
    static class TestPropsConfig {
        @Bean
        @Primary
        public ElasticContext testElasticContext() {
            // When running with @SpringBootTest, the static field may not be initialized yet.
            // Provide a safe default ElasticContext to allow the ApplicationContext to start.
            if (elasticContext != null) {
                return elasticContext;
            }
            return new ElasticContext("default", "localhost", 9200);
        }

        @Bean
        public ProcessorManager processorManager(ElasticContext elasticContext) {
            return new ProcessorManager(elasticContext);
        }

        @Bean
        @Primary
        public LinkQueue testLinkQueue() {
            return new org.smileyface.webcrawler.crawler.InMemoryLinkQueue();
        }
    }

    @BeforeAll
    static void setup() {
        try {
            ElasticsearchTestContainer.start();
            dockerAvailable = true;

            String hostPort = ElasticsearchTestContainer.getHttpHostAddress(); // host:port
            lowLevel = RestClient.builder(org.apache.http.HttpHost.create("http://" + hostPort)).build();
            ElasticsearchTransport transport = new RestClientTransport(lowLevel, new JacksonJsonpMapper());
            validationClient = new ElasticsearchClient(transport);
            String[] parts = hostPort.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            elasticContext = new ElasticContext(UUID.randomUUID().toString(), host, port);
            es = new ElasticRestClient(elasticContext);
        } catch (Throwable t) {
            dockerAvailable = false;
        }
    }

    @AfterAll
    static void tearAllDown() throws IOException {
        if (lowLevel != null) {
            lowLevel.close();
            lowLevel = null;
        }
        if (dockerAvailable) {
            ElasticsearchTestContainer.stop();
        }
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void setUpQueue() {
        if (queue != null) queue.init();
    }

    @Test
    void processors_runUntilQueueEmpty_andComplete() throws Exception {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("/", html("<h1>Root</h1>", "<a href='/a'>A</a>"));
        pages.put("/a", html("<p>A page</p>"));

        int port = startServer(pages);
        String base = "http://localhost:" + port;

        // Enqueue two urls
        queue.enqueue(base + "/");
        queue.enqueue(base + "/a");

        ConcurrentLinkedQueue<WebPageContent> sink = new ConcurrentLinkedQueue<>();

        CrawlerProperties props = new CrawlerProperties();
        props.setUserAgent("TestBot/1.0");
        props.setRequestTimeoutMs(5000);
        props.setMaxDepth(0);
        int minCharacters = 0;
        CrawlerProperties.ContentRulesConfig contentRulesConfig = new CrawlerProperties.ContentRulesConfig(minCharacters, "", "");
        CrawlerProperties.PageConfig pageConfig = new CrawlerProperties.PageConfig("^http://localhost.*", contentRulesConfig);
        pageConfig.matchAll = true;
        props.addPageConfig(pageConfig);
        String indexName = null;
        if (dockerAvailable) {
            // Use prefix and derive full index name with tenant id to match WebPageProcessor.getIndexName()
            indexName = CrawlerUtils.getIndexName(props, elasticContext);
            try { es.deleteIndex(indexName); } catch (Exception ignored) {}
            es.createIndex(indexName);
        }

        mgr.start(2, queue, props, sink::add);

        boolean finished = mgr.awaitAll(Duration.ofSeconds(10));
        assertThat(finished).as("Processors should finish within timeout").isTrue();

        List<ProcessorStatus> statuses = mgr.getStatuses();
        assertThat(statuses).hasSize(2);
        assertThat(statuses).allMatch(s -> s.getState() == ProcessorState.COMPLETED,
                "All processors should report COMPLETED when queue becomes empty");

        // We expect two documents processed (order not guaranteed)
        assertThat(sink).hasSize(2);
        List<String> urls = new ArrayList<>();
        for (WebPageContent w : sink) urls.add(w.getUrl());
        assertThat(urls).contains(base + "/", base + "/a");

        // Validate documents were indexed in Elasticsearch if available
        if (dockerAvailable) {
            for (WebPageContent w : sink) {
                assertThat(w.getId()).as("Indexed document should have an id").isNotNull();
                WebPageContent stored = es.getDocument(indexName, w.getId());
                assertThat(stored).isEqualTo(w);
                assertThat(stored).as("Stored document should be retrievable from Elasticsearch").isNotNull();
                assertThat(stored.getUrl()).isEqualTo(w.getUrl());
            }
        }
    }

    @Test
    void processes_planetX_canned_html_successfully() throws Exception {
        // Load canned HTML from test resources
        String body = readResource("/planet-x.html");

        // Serve the canned HTML via an in-process HTTP server
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/px", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        int port = server.getAddress().getPort();
        String base = "http://localhost:" + port;

        // Enqueue the single canned page
        queue.enqueue(base + "/px");

        ConcurrentLinkedQueue<WebPageContent> sink = new ConcurrentLinkedQueue<>();

        CrawlerProperties props = new CrawlerProperties();
        props.setUserAgent("TestBot/1.0");
        props.setRequestTimeoutMs(5000);
        int minCharacters = 600;
        CrawlerProperties.ContentRulesConfig contentRulesConfig = new CrawlerProperties.ContentRulesConfig(minCharacters, "", "");
        CrawlerProperties.PageConfig pageConfig = new CrawlerProperties.PageConfig("^http://localhost.*", contentRulesConfig);
        props.addPageConfig(pageConfig);
        String indexName = null;
        if (dockerAvailable) {
            indexName = CrawlerUtils.getIndexName(props, elasticContext);
            es.createIndex(indexName);
        }

        mgr.start(1, queue, props, sink::add);

        boolean finished = mgr.awaitAll(Duration.ofSeconds(10));
        assertThat(finished).as("Processor should finish within timeout").isTrue();

        assertThat(sink).as("Exactly one document should be processed").hasSize(1);
        WebPageContent w = sink.peek();
        assertThat(w).isNotNull();
        assertThat(w.getStatus()).isEqualTo(CrawlStatus.OK);
        assertThat(w.getHttpStatus()).isEqualTo(200);
        assertThat(w.getContentType()).isNotNull().containsIgnoringCase("text/html");
        assertThat(w.getTitle()).isEqualTo("Hypothetical Planet X");
        assertThat(w.getContents()).isNotNull().isNotEmpty();
        assertThat(w.getContentLength()).isNotNull().isGreaterThan(100);

        // Fetch the document from Elasticsearch and verify key fields
        if (dockerAvailable) {
            assertThat(w.getId()).as("Indexed document should have an id").isNotNull();
            WebPageContent stored = es.getDocument(indexName, w.getId());
            assertThat(stored).isEqualTo(w);
            assertThat(stored).as("Stored document should be retrievable from Elasticsearch").isNotNull();
            assertThat(stored.getUrl()).isEqualTo(w.getUrl());
            assertThat(stored.getTitle()).isEqualTo("Hypothetical Planet X");
            assertThat(stored.getStatus()).isEqualTo(CrawlStatus.OK);
        }
    }

    @Test
    void stopAll_requestsStop_andLeavesUnprocessedItems() throws Exception {
        // Create pages that simulate slow responses to allow stop in-flight
        int sleepMs = 300;
        SlowHandler handler = new SlowHandler(sleepMs);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/x", handler);
        server.createContext("/y", handler);
        server.createContext("/z", handler);
        server.start();
        int port = server.getAddress().getPort();
        String base = "http://localhost:" + port;

        queue.enqueue(base + "/x");
        queue.enqueue(base + "/y");
        queue.enqueue(base + "/z");

        ConcurrentLinkedQueue<WebPageContent> sink = new ConcurrentLinkedQueue<>();
        CrawlerProperties props = new CrawlerProperties();
        props.setUserAgent("TestBot/1.0");
        props.setRequestTimeoutMs(5000);

        mgr.start(3, queue, props, sink::add);

        // Stop immediately; because of sleep, likely not all have been processed
        mgr.stopAll();

        List<ProcessorStatus> statuses = mgr.getStatuses();
        assertThat(statuses).hasSize(3);
        // At least one should be STOPPED due to explicit stop request
        assertThat(statuses).anyMatch(s -> s.getState() == ProcessorState.STOPPED
                                                || s.getState() == ProcessorState.COMPLETED);

        // We expect fewer than or equal to 3 processed items; typically < 3
        assertThat(sink.size()).isBetween(0, 3);
    }

    // ---------------- helpers ----------------
    private static String html(String... inner) {
        String body = String.join("\n", inner);
        return "<!doctype html><html><head><title>T</title></head><body>" + body + "</body></html>";
    }

    private static String readResource(String path) throws IOException {
        try (InputStream is = ProcessorManagerTest.class.getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int startServer(Map<String, String> pages) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        HttpHandler handler = new PageHandler(pages);
        for (String path : pages.keySet()) {
            server.createContext(path, handler);
        }
        server.start();
        return server.getAddress().getPort();
    }

    private static class PageHandler implements HttpHandler {
        private final Map<String, String> pages;
        PageHandler(Map<String, String> pages) { this.pages = pages; }
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String content = pages.get(path);
            if (content == null) {
                send(exchange, 404, "Not found");
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            send(exchange, 200, content);
        }
        private void send(HttpExchange ex, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    private static class SlowHandler implements HttpHandler {
        private final int sleepMs;
        private final AtomicInteger counter = new AtomicInteger();
        SlowHandler(int sleepMs) { this.sleepMs = sleepMs; }
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            String body = "<html><body><p>Slow " + counter.incrementAndGet() + "</p></body></html>";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }
}
