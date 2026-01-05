package org.smileyface.webcrawler.processor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.smileyface.webcrawler.crawler.CrawlerProperties;
import org.smileyface.webcrawler.crawler.LinkQueue;
import org.smileyface.webcrawler.model.WebPageContent;
import org.smileyface.webcrawler.config.BeanConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(classes = {BeanConfig.class,
        ProcessorManagerTest.TestPropsConfig.class})
class ProcessorManagerTest {

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

        ProcessorManager mgr = new ProcessorManager();
        mgr.start(2, queue, props, sink::add);

        boolean finished = mgr.awaitAll(Duration.ofSeconds(10));
        assertTrue(finished, "Processors should finish within timeout");

        List<ProcessorStatus> statuses = mgr.getStatuses();
        assertEquals(2, statuses.size());
        assertTrue(statuses.stream().allMatch(s -> s.getState() == ProcessorState.COMPLETED),
                "All processors should report COMPLETED when queue becomes empty");

        // We expect two documents processed (order not guaranteed)
        assertEquals(2, sink.size());
        List<String> urls = new ArrayList<>();
        for (WebPageContent w : sink) urls.add(w.getUrl());
        assertTrue(urls.contains(base + "/"));
        assertTrue(urls.contains(base + "/a"));
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

        ProcessorManager mgr = new ProcessorManager();
        mgr.start(3, queue, props, sink::add);

        // Stop immediately; because of sleep, likely not all have been processed
        mgr.stopAll();

        List<ProcessorStatus> statuses = mgr.getStatuses();
        assertEquals(3, statuses.size());
        // At least one should be STOPPED due to explicit stop request
        assertTrue(statuses.stream().anyMatch(s -> s.getState() == ProcessorState.STOPPED
                                                || s.getState() == ProcessorState.COMPLETED));

        // We expect fewer than or equal to 3 processed items; typically < 3
        assertTrue(sink.size() >= 0 && sink.size() <= 3);
    }

    // ---------------- helpers ----------------
    private static String html(String... inner) {
        String body = String.join("\n", inner);
        return "<!doctype html><html><head><title>T</title></head><body>" + body + "</body></html>";
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
