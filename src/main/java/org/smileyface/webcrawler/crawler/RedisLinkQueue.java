package org.smileyface.webcrawler.crawler;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed distributed implementation of LinkQueue.
 *
 * Uses a Redis Set for deduplication ("{ns}:seen") and a Redis List for queueing ("{ns}:queue").
 * Only new URLs (per Set membership) are appended to the List.
 */
@Component
public class RedisLinkQueue implements LinkQueue {

    private final StringRedisTemplate redis;
    private final String seenKey;
    private final String queueKey;

    @Autowired
    public RedisLinkQueue(StringRedisTemplate redisTemplate, CrawlerProperties properties) {
        this.redis = redisTemplate;
        String ns = properties.getQueueNamespace();
        this.seenKey = ns + ":seen";
        this.queueKey = ns + ":queue";
    }

    @Override
    public void enqueue(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        // SADD returns 1 if the element was added, 0 if it was already present
        Long added = redis.opsForSet().add(seenKey, url);
        if (added != null && added > 0) {
            redis.opsForList().rightPush(queueKey, url);
        }
    }

    @Override
    public String deQueue() {
        // Non-blocking pop from the left (FIFO). Returns null if list is empty.
        return redis.opsForList().leftPop(queueKey);
    }

    @Override
    public void init() {
        // Remove both the queue list and the deduplication set so the state is fully reset.
        try {
            redis.delete(queueKey);
            redis.delete(seenKey);
        } catch (Exception ignored) {
            // Best-effort reset; ignore errors to keep idempotent behavior
        }
    }
}
