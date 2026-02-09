package org.smileyface.webcrawler.crawler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Disabled("Consolidated into LinkQueueParameterizedTest")
class RedisLinkQueueTest {

    private static GenericContainer<?> redis;
    private static boolean dockerAvailable;

    @BeforeAll
    static void startRedis() {
        try {
            redis = new GenericContainer<>("redis:7.2.4").withExposedPorts(6379);
            redis.start();
            dockerAvailable = true;
        } catch (Throwable t) {
            // Docker not available in this environment; mark tests to be skipped via assumptions
            dockerAvailable = false;
        }
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.stop();
        }
    }

    private RedisLinkQueue newQueue(String namespace) {
        String host = redis.getHost();
        Integer port = redis.getMappedPort(6379);
        LettuceConnectionFactory cf = new LettuceConnectionFactory(host, port);
        cf.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(cf);
        CrawlerProperties props = new CrawlerProperties();
        props.setQueueNamespace(namespace);
        return new RedisLinkQueue(template, props);
    }

    @Test
    void emptyQueueDequeueReturnsNull() {
        Assumptions.assumeTrue(dockerAvailable, "Docker is not available; skipping RedisLinkQueue tests");
        RedisLinkQueue q = newQueue("test:" + UUID.randomUUID());
        assertThat(q.deQueue()).isNull();
    }

    @Test
    void enqueueNullOrBlankIsIgnored() {
        Assumptions.assumeTrue(dockerAvailable, "Docker is not available; skipping RedisLinkQueue tests");
        RedisLinkQueue q = newQueue("test:" + UUID.randomUUID());
        q.enqueue(null);
        q.enqueue("   ");
        assertThat(q.deQueue()).isNull();
    }

    @Test
    void deduplicationPreventsDuplicatesAndPersistsAcrossDequeue() {
        Assumptions.assumeTrue(dockerAvailable, "Docker is not available; skipping RedisLinkQueue tests");
        RedisLinkQueue q = newQueue("test:" + UUID.randomUUID());
        String url = "https://example.com/";

        q.enqueue(url);
        q.enqueue(url); // duplicate ignored

        assertThat(q.deQueue()).isEqualTo(url);
        assertThat(q.deQueue()).isNull();

        // Re-enqueue after dequeue should still be ignored due to seen set
        q.enqueue(url);
        assertThat(q.deQueue()).isNull();
    }

    @Test
    void fifoOrderWithDeduplication() {
        Assumptions.assumeTrue(dockerAvailable, "Docker is not available; skipping RedisLinkQueue tests");
        RedisLinkQueue q = newQueue("test:" + UUID.randomUUID());
        String a = "https://a.example/";
        String b = "https://b.example/";
        String c = "https://c.example/";

        q.enqueue(a);
        q.enqueue(b);
        q.enqueue(a); // duplicate should not affect queue
        q.enqueue(c);

        assertThat(q.deQueue()).isEqualTo(a);
        assertThat(q.deQueue()).isEqualTo(b);
        assertThat(q.deQueue()).isEqualTo(c);
        assertThat(q.deQueue()).isNull();
    }
}
