package org.smileyface.webcrawler.crawler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A simple in-memory implementation of LinkQueue using a {@link java.util.concurrent.ConcurrentLinkedQueue}
 * for FIFO ordering and a concurrent {@link java.util.Set} for deduplication.
 *
 * <p>Why a queue (ConcurrentLinkedQueue) and not a set (HashSet)?</p>
 * <ul>
 *   <li><b>Purpose</b>: a queue models processing order (first-in-first-out) for URLs to crawl, while a
 *   set only models membership/uniqueness with no ordering.</li>
 *   <li><b>Thread-safety</b>: {@code ConcurrentLinkedQueue} is lock-free and thread-safe for concurrent
 *   producers/consumers. A plain {@code HashSet} is <em>not</em> thread-safe; using it directly in a
 *   concurrent crawler would be unsafe without external synchronization. Here we use
 *   {@code ConcurrentHashMap.newKeySet()} as the thread-safe deduplication set.</li>
 *   <li><b>Semantics</b>: the queue preserves insertion order for dequeueing; the set simply ensures we do not
 *   enqueue the same URL twice. Combining both gives FIFO processing with uniqueness.</li>
 *   <li><b>Null handling</b>: both the queue and the set disallow {@code null} in this implementation (we ignore
 *   null/blank inputs in {@link #enqueue(String)}).</li>
 * </ul>
 */
@Component
@ConditionalOnMissingBean(LinkQueue.class)
public class InMemoryLinkQueue implements LinkQueue {

    private final Queue<String> queue = new ConcurrentLinkedQueue<>();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public void enqueue(String url) {
        if (url == null || url.isBlank()) return;
        // dedupe enqueue
        if (seen.add(url)) {
            queue.add(url);
        }
    }

    // Additional accessors could be added later (e.g., poll/size), but are
    // intentionally omitted per current requirements.

    @Override
    public String deQueue() {
        // Non-blocking retrieval of the next URL, or null if empty.
        return queue.poll();
    }

    @Override
    public void init() {
        // Clear both the FIFO queue and the deduplication set, allowing URLs to be enqueued again.
        queue.clear();
        seen.clear();
    }
}
