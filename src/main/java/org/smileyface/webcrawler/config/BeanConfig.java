package org.smileyface.webcrawler.config;

import org.smileyface.webcrawler.crawler.CrawlerProperties;
import org.smileyface.webcrawler.crawler.InMemoryLinkQueue;
import org.smileyface.webcrawler.crawler.LinkQueue;
import org.smileyface.webcrawler.crawler.RedisLinkQueue;
import org.smileyface.webcrawler.elasticsearch.ElasticContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires an {@link ElasticContext} Spring bean using host and port from
 * system environment variables.
 *
 * Environment variables used (with defaults):
 * - ELASTIC_HOST: default "localhost"
 * - ELASTIC_PORT: default "9200"
 */
@Configuration
public class BeanConfig {

    @Value("${crawler.queue.type:in-memory}")
    private String queueType;

    @Bean
    public ElasticContext elasticContext() {
        String host = System.getenv().getOrDefault("ELASTIC_HOST", "localhost");
        String portStr = System.getenv().getOrDefault("ELASTIC_PORT", "9200");
        int port;
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            port = 9200;
        }
        // Tenant is not specified by the requirement; use a generic default
        return new ElasticContext("default", host, port);
    }

    /**
     * Selects the LinkQueue implementation based on the configuration property
     * {@code crawler.queue.type}. Supported values:
     * - "in-memory" (default): uses {@link InMemoryLinkQueue}
     * - "redis": uses {@link RedisLinkQueue} when a {@link StringRedisTemplate} is available;
     *   falls back to {@link InMemoryLinkQueue} otherwise.
     */
    @Bean
    public LinkQueue linkQueue(ObjectProvider<StringRedisTemplate> redisProvider,
                               CrawlerProperties properties) {
        String kind = queueType == null ? "in-memory" : queueType.trim().toLowerCase();
        if ("redis".equals(kind)) {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            if (template != null) {
                return new RedisLinkQueue(template, properties);
            }
        }
        return new InMemoryLinkQueue();
    }
}
