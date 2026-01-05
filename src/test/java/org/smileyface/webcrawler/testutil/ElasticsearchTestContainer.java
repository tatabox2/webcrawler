package org.smileyface.webcrawler.testutil;

import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Test utility to manage a singleton Elasticsearch Testcontainers instance.
 *
 * Usage (JUnit 5 example):
 * <pre>
 *   @BeforeAll
 *   static void initEs() {
 *     ElasticsearchTestContainer.start();
 *   }
 *
 *   @AfterAll
+ *   static void tearDownEs() {
 *     ElasticsearchTestContainer.stop();
 *   }
 *
 *   @Test
 *   void canConnect() {
 *     String address = ElasticsearchTestContainer.getHttpHostAddress();
 *     // use address (e.g., http://localhost:xxxxx) in your client
 *   }
 * </pre>
 */
public final class ElasticsearchTestContainer {

    private static final DockerImageName IMAGE = DockerImageName
            .parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3")
            // allow remapping if needed by local caches/registries
            .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch");

    private static ElasticsearchContainer container;

    private ElasticsearchTestContainer() {
        // utility
    }

    /**
     * Starts the Elasticsearch test container if it is not already running.
     * This method is idempotent and thread-safe.
     */
    public static synchronized void start() {
        if (container != null && container.isRunning()) {
            return;
        }
        container = new ElasticsearchContainer(IMAGE)
                .withEnv("discovery.type", "single-node")
                .withEnv("xpack.security.enabled", "false");
        container.start();
    }

    /**
     * Stops the Elasticsearch test container if it is running.
     * This method is idempotent and thread-safe.
     */
    public static synchronized void stop() {
        if (container != null) {
            try {
                container.stop();
            } finally {
                container = null;
            }
        }
    }

    /**
     * Returns the HTTP host address (host:port) for the running container.
     * @throws IllegalStateException if the container is not running
     */
    public static String getHttpHostAddress() {
        ensureStarted();
        return container.getHttpHostAddress();
    }

    /**
     * Username for Elasticsearch. When security is disabled for the container,
     * this returns null.
     */
    public static String getUsername() {
        return null; // security disabled
    }

    /**
     * Password for Elasticsearch. When security is disabled for the container,
     * this returns null.
     */
    public static String getPassword() {
        return null; // security disabled
    }

    private static void ensureStarted() {
        if (container == null || !container.isRunning()) {
            throw new IllegalStateException("ElasticsearchTestContainer is not running. Call start() first.");
        }
    }
}
