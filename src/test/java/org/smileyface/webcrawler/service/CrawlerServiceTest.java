package org.smileyface.webcrawler.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.smileyface.webcrawler.crawler.CrawlerProperties;
import org.smileyface.webcrawler.crawler.InMemoryLinkQueue;
import org.smileyface.webcrawler.crawler.LinkQueue;
import org.smileyface.webcrawler.crawler.RedisLinkQueue;
import org.smileyface.webcrawler.elasticsearch.ElasticContext;
import org.smileyface.webcrawler.elasticsearch.ElasticRestClient;
import org.smileyface.webcrawler.processor.ProcessorManager;
import org.smileyface.webcrawler.testutil.ElasticsearchTestContainer;
import org.smileyface.webcrawler.util.CrawlerUtils;
import org.smileyface.webcrawler.model.WebPageContent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for CrawlerService using a lightweight in-memory HTTP server
 * that serves multiple generated HTML pages.
 */
@SpringBootTest
@ActiveProfiles("test")
class CrawlerServiceTest {

    Logger logger = LogManager.getLogger(CrawlerServiceTest.class);

    private HttpServer server;
    private static boolean dockerAvailable;
    private static RestClient lowLevel;
    private static ElasticsearchClient validationClient;
    private static ElasticRestClient es;
    private static ElasticContext elasticContext;
    private static GenericContainer<?> redisContainer;

    @Autowired
    private CrawlerProperties crawlerProperties;

    @Autowired
    private LinkQueue queue;

    @Autowired
    private CrawlerService crawler;

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

        // Start Redis Testcontainer independently; if this fails, we will fall back to in-memory queue
        try {
            redisContainer = new GenericContainer<>("redis:7.2.4").withExposedPorts(6379);
            redisContainer.start();
        } catch (Throwable t) {
            // leave redisContainer as null; tests will still pass using in-memory queue
        }
    }

    @TestConfiguration
    @EnableConfigurationProperties(CrawlerProperties.class)
    static class TestPropsConfig {

        @Bean
        @Primary
        ElasticContext testeEasticContext() {
            // Provide a safe default when static field isn't initialized yet
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
        public CrawlerService crawler(LinkQueue queue, ProcessorManager processorManager, CrawlerProperties crawlerProperties) {
            return new CrawlerService(queue, crawlerProperties,processorManager, elasticContext);
        }

        /**
         * Override LinkQueue bean to use RedisLinkQueue backed by Testcontainers Redis when available.
         * Falls back to InMemoryLinkQueue if Redis container failed to start.
         */
        @Bean
        @Primary
        public LinkQueue testLinkQueue(CrawlerProperties properties) {
            if (redisContainer != null && redisContainer.isRunning()) {
                String host = redisContainer.getHost();
                Integer port = redisContainer.getMappedPort(6379);
                LettuceConnectionFactory cf = new LettuceConnectionFactory(host, port);
                cf.afterPropertiesSet();
                StringRedisTemplate template = new StringRedisTemplate(cf);
                // Use a distinct namespace to avoid clashes across test runs
                properties.setQueueNamespace("test:" + UUID.randomUUID());
                return new RedisLinkQueue(template, properties);
            }
            return new InMemoryLinkQueue();
        }
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @AfterAll
    static void stopContainers() {
        if (redisContainer != null) {
            try {
                redisContainer.stop();
            } finally {
                redisContainer = null;
            }
        }
        // Keep Elasticsearch container lifecycle as-is; it will be cleaned by JVM shutdown if not explicitly stopped
    }

    @BeforeEach
    void setUpQueue() {
        if (queue != null) queue.init();
    }

    private static Stream<Arguments> testDataProvider() {
        return Stream.of(
                    // Arguments.arguments( "planet-x.html", ".*\\.nasa.gov/.*"),
                    Arguments.arguments("t23389-topic.html", ".*\\.666forum.com/.*"),
                    Arguments.arguments("t18300-topic.html", ".*\\.666forum.com/.*")
                );
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

        // This is delibrately only testing the linkQueue.  Doesn't test process manager
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
        // Delibrately,this only test the linkQueue.  Doesn't test process manager
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

        // Delibrately,this only test the linkQueue.  Doesn't test process manager
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

    @ParameterizedTest (name="file={0}, includedPattern={1}")
    @MethodSource ("testDataProvider")
    void crawl_maxDepth0_withPlanetXResource_enqueuesNoExternalLinks(String fileName, String includedPattern) throws Exception {
        // Load the large HTML from test resources and serve it at the root path
        String planetX;
        try (var is = getClass().getResourceAsStream("/" + fileName)) {
            assertThat(is).as("planet-x.html should be on classpath").isNotNull();
            planetX = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("/", planetX);

        int port = startServer(pages);
        String base = "http://localhost:" + port;

        crawlerProperties.setMaxDepth(0);
        int minCharacters = 0;
        CrawlerProperties.ContentRulesConfig contentRulesConfig = new CrawlerProperties.ContentRulesConfig(minCharacters, "", "");
        contentRulesConfig.elementStyle="font-size: 24px; line-height: normal";
        CrawlerProperties.PageConfig pageConfig = new CrawlerProperties.PageConfig("^http://localhost.*", contentRulesConfig);
        pageConfig.matchAll = true;
        crawlerProperties.addPageConfig(pageConfig);

        // Only accept localhost links so we don't enqueue any external URLs from the sample HTML
        crawlerProperties.setIncludeUrlPatterns(List.of(includedPattern));
        crawlerProperties.setMaxDepth(1);

        crawler.crawl(base, true);

        Set<String> enqueued = drain(queue);
        // The sample HTML does not contain localhost links, so nothing should be enqueued
        logger.info("queue size: {}", enqueued.size());
        assertThat(enqueued).isEmpty();

        // Validate documents indexed into Elasticsearch and check the contentLength field
        if (dockerAvailable) {
            String indexName = CrawlerUtils.getIndexName(crawlerProperties, elasticContext);
            assertThat(indexName).isNotBlank();

            // Poll a few times as indexing can be asynchronous
            List<WebPageContent> docs = List.of();
            for (int i = 0; i < 10; i++) {
                docs = es.searchAll(indexName);
                if (!docs.isEmpty()) break;
                Thread.sleep(200L);
            }
            assertThat(docs).isNotEmpty();
            // Ensure at least one document has a non-zero contentLength
            assertThat(docs.stream().allMatch(d -> d.getContentLength() > minCharacters)).isTrue();
        }
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
