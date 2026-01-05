package org.smileyface.webcrawler.crawler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for LinkQueue implementations using JUnit 5 parameterized tests.
 * This consolidates tests for both InMemoryLinkQueue and RedisLinkQueue.
 */
class LinkQueueParameterizedTest {

    private static Logger logger = LogManager.getLogger(LinkQueueParameterizedTest.class);
    private static GenericContainer<?> redisContainer; // lazily started
    private static List<Arguments> IMPLEMENTATIONS; // cached implementations list

    static Stream<Arguments> queueImplementations() {
        if (IMPLEMENTATIONS == null) {
            synchronized (LinkQueueParameterizedTest.class) {
                if (IMPLEMENTATIONS == null) {
                    IMPLEMENTATIONS = new ArrayList<>();
                    // In-memory implementation is always available
                    IMPLEMENTATIONS.add(Arguments.of(
                            "InMemoryLinkQueue",
                            (Supplier<LinkQueue>) InMemoryLinkQueue::new
                    ));

                    // Try to start Redis Testcontainer and add Redis-backed implementation if available
                    try {
                        redisContainer = new GenericContainer<>("redis:7.2.4").withExposedPorts(6379);
                        redisContainer.start();

                        Supplier<LinkQueue> redisSupplier = () -> {
                            String host = redisContainer.getHost();
                            Integer port = redisContainer.getMappedPort(6379);
                            LettuceConnectionFactory cf = new LettuceConnectionFactory(host, port);
                            cf.afterPropertiesSet();
                            StringRedisTemplate template = new StringRedisTemplate(cf);
                            CrawlerProperties props = new CrawlerProperties();
                            props.setQueueNamespace("test:" + UUID.randomUUID());
                            return new RedisLinkQueue(template, props);
                        };

                        IMPLEMENTATIONS.add(Arguments.of("RedisLinkQueue", redisSupplier));
                    } catch (Throwable t) {
                        // add logging for exception message
                        logger.error("Failed to start Redis Testcontainer: {}", t.getMessage(), t);
                        // Docker not available — silently skip Redis implementation
                    }
                }
            }
        }
        return IMPLEMENTATIONS.stream();
    }

    @AfterAll
    static void tearDown() {
        if (redisContainer != null) {
            try {
                redisContainer.stop();
            } finally {
                redisContainer = null;
            }
        }
    }

    @ParameterizedTest(name = "{index} => impl={0}")
    @MethodSource("queueImplementations")
    @DisplayName("emptyQueueDequeueReturnsNull")
    void emptyQueueDequeueReturnsNull(String implName, Supplier<LinkQueue> supplier) {
        LinkQueue q = supplier.get();
        assertNull(q.deQueue());
    }

    @ParameterizedTest(name = "{index} => impl={0}")
    @MethodSource("queueImplementations")
    @DisplayName("enqueueNullOrBlankIsIgnored")
    void enqueueNullOrBlankIsIgnored(String implName, Supplier<LinkQueue> supplier) {
        LinkQueue q = supplier.get();
        q.enqueue(null);
        q.enqueue("   ");
        assertNull(q.deQueue());
    }

    @ParameterizedTest(name = "{index} => impl={0}")
    @MethodSource("queueImplementations")
    @DisplayName("deduplicationPreventsDuplicates")
    void deduplicationPreventsDuplicates(String implName, Supplier<LinkQueue> supplier) {
        LinkQueue q = supplier.get();
        String url = "https://example.com/";

        q.enqueue(url);
        q.enqueue(url); // duplicate

        assertEquals(url, q.deQueue());
        assertNull(q.deQueue()); // only one instance should be present

        // Re-enqueue after dequeue is still ignored due to dedupe set retention
        q.enqueue(url);
        assertNull(q.deQueue());
    }

    @ParameterizedTest(name = "{index} => impl={0}")
    @MethodSource("queueImplementations")
    @DisplayName("fifoOrderWithDeduplication")
    void fifoOrderWithDeduplication(String implName, Supplier<LinkQueue> supplier) {
        LinkQueue q = supplier.get();
        String a = "https://a.example/";
        String b = "https://b.example/";
        String c = "https://c.example/";

        q.enqueue(a);
        q.enqueue(b);
        q.enqueue(a); // duplicate should not change order
        q.enqueue(c);

        assertEquals(a, q.deQueue());
        assertEquals(b, q.deQueue());
        assertEquals(c, q.deQueue());
        assertNull(q.deQueue());
    }

    @ParameterizedTest(name = "{index} => impl={0}")
    @MethodSource("queueImplementations")
    @DisplayName("init_clearsQueueAndResetsDeduplication")
    void init_clearsQueueAndResetsDeduplication(String implName, Supplier<LinkQueue> supplier) {
        LinkQueue q = supplier.get();
        String a = "https://a.example/";
        String b = "https://b.example/";

        // Enqueue two items, then clear
        q.enqueue(a);
        q.enqueue(b);
        q.init();

        // Queue should be empty after init
        assertNull(q.deQueue(), "Queue should be empty immediately after init()");

        // Deduplication should be reset: re-enqueue previously seen URLs should be accepted
        q.enqueue(a);
        assertEquals(a, q.deQueue());
        assertNull(q.deQueue());

        // Now, without another init, re-enqueue should be ignored due to dedupe retention
        q.enqueue(a);
        assertNull(q.deQueue(), "Re-enqueue without re-init should be ignored due to dedupe retention");

        // After init again, it should be allowed
        q.init();
        q.enqueue(a);
        assertEquals(a, q.deQueue());
        assertNull(q.deQueue());
    }
}
