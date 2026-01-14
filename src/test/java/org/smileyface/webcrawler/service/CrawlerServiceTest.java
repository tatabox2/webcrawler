package org.smileyface.webcrawler.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.smileyface.webcrawler.crawler.CrawlerProperties;
import org.smileyface.webcrawler.crawler.LinkQueue;
import org.smileyface.webcrawler.config.BeanConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for CrawlerService using a lightweight in-memory HTTP server
 * that serves multiple generated HTML pages.
 */
@SpringJUnitConfig(classes = {BeanConfig.class,
        CrawlerServiceTest.TestPropsConfig.class})
class CrawlerServiceTest {

    private HttpServer server;

    @Autowired
    private LinkQueue queue;

    @TestConfiguration
    @EnableConfigurationProperties(CrawlerProperties.class)
    static class TestPropsConfig { }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void setUpQueue() {
        if (queue != null) queue.init();
    }

    @Test
    void crawl_maxDepth0_enqueuesLinksFromEntryOnly() throws Exception {
        Map<String, String> pages = new LinkedHashMap<>();
        // Root page has links to /a and /b and a non-http link
        pages.put("/", html(
                "<a href='/a'>A</a>",
                "<a href='/b'>B</a>",
                "<a href='mailto:test@example.com'>mail</a>"
        ));
        // Child pages (may contain further links but should not be traversed at depth 0)
        pages.put("/a", html("<a href='/a1'>A1</a>"));
        pages.put("/b", html("<a href='/b1'>B1</a>"));

        int port = startServer(pages);
        String entry = "http://localhost:" + port + "/";

        CrawlerProperties props = new CrawlerProperties();
        props.setMaxDepth(0);
        CrawlerService crawler = new CrawlerService(queue, props);

        crawler.crawl(entry);

        Set<String> enqueued = drain(queue);
        // Expect only links found on the entry page, normalized with port and without fragments
        Set<String> expected = Set.of(
                "http://localhost:" + port + "/a",
                "http://localhost:" + port + "/b"
        );
        assertThat(enqueued).isEqualTo(expected);
    }

    @Test
    void crawl_maxDepth1_traversesOnceAndEnqueuesLinksFromDepth0And1() throws Exception {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("/", html(
                "<a href='/a'>A</a>",
                "<a href='/b#frag'>B with fragment</a>",
                "<a href='javascript:void(0)'>js</a>"
        ));
        pages.put("/a", html(
                "<a href='/a1'>A1</a>",
                "<a href='/a2'>A2</a>"
        ));
        pages.put("/b", html(
                "<a href='/a'>Back to A</a>",
                "<a href='/b1'>B1</a>"
        ));
        // leaf pages
        pages.put("/a1", html());
        pages.put("/a2", html());
        pages.put("/b1", html());

        int port = startServer(pages);
        String base = "http://localhost:" + port;

        CrawlerProperties props = new CrawlerProperties();
        props.setMaxDepth(1);
        CrawlerService crawler = new CrawlerService(queue, props);

        crawler.crawl(base + "/");

        Set<String> enqueued = drain(queue);
        // At depth 1 we enqueue links from root (depth 0) and from /a and /b (depth 1)
        Set<String> expected = Set.of(
                base + "/a",
                base + "/b",
                base + "/a1",
                base + "/a2",
                base + "/b1"
        );
        assertThat(enqueued).isEqualTo(expected);
    }

    @Test
    void crawl_withIncludeExcludeFilters_appliesCorrectly() throws Exception {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("/", html(
                "<a href='/a'>A</a>",
                "<a href='/b'>B</a>"
        ));
        pages.put("/a", html(
                "<a href='/a1'>A1</a>",
                "<a href='/a2'>A2</a>"
        ));
        pages.put("/b", html(
                "<a href='/b1'>B1</a>",
                "<a href='/b2'>B2</a>"
        ));
        pages.put("/a1", html());
        pages.put("/a2", html());
        pages.put("/b1", html());
        pages.put("/b2", html());

        int port = startServer(pages);
        String base = "http://localhost:" + port;

        CrawlerProperties props = new CrawlerProperties();
        props.setMaxDepth(1);
        // Include only URLs containing "/a", but exclude those ending with "/a2"
        props.setIncludeUrlPatterns(List.of(".*/a.*"));
        props.setExcludeUrlPatterns(List.of(".*/a2$"));

        CrawlerService crawler = new CrawlerService(queue, props);

        crawler.crawl(base + "/");

        Set<String> enqueued = drain(queue);
        Set<String> expected = Set.of(
                base + "/a",
                base + "/a1"
        );
        assertThat(enqueued).isEqualTo(expected);
        // Ensure no b* links slipped through
        assertThat(enqueued.stream().noneMatch(u -> u.contains("/b"))).isTrue();
    }

    // ----------------- helpers -----------------

    private static String html(String... inner) {
        String body = String.join("\n", inner);
        return "<!doctype html><html><head><title>T</title></head><body>" + body + "</body></html>";
    }

    private int startServer(Map<String, String> pages) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        HttpHandler handler = new PageHandler(pages);
        // Register all provided paths
        for (String path : pages.keySet()) {
            server.createContext(path, handler);
        }
        // Also ensure root context exists
        if (!pages.containsKey("/")) {
            server.createContext("/", handler);
        }
        server.setExecutor(null);
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

    private static Set<String> drain(LinkQueue queue) {
        List<String> urls = new ArrayList<>();
        for (;;) {
            String u = queue.deQueue();
            if (u == null) break;
            urls.add(u);
        }
        return urls.stream().collect(Collectors.toSet());
    }
}
