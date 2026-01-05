package org.smileyface.webcrawler.crawler;

/**
 * Simple abstraction for a queue that stores discovered links for later processing.
 */
public interface LinkQueue {

    /**
     * Enqueue a URL string for later processing. Implementations may apply deduplication.
     * @param url normalized absolute URL
     */
    void enqueue(String url);

    /**
     * Dequeue the next URL for processing in FIFO order.
     * This is a non-blocking operation and returns null when the queue is empty.
     *
     * Note: Implementations that also maintain a deduplication set typically do not
     * remove entries from that set on dequeue. This means re-enqueuing the same URL
     * may be ignored until the dedupe set is cleared by other means.
     *
     * @return next URL or null if none
     */
    String deQueue();

    /**
     * Initialize or reset the queue state by clearing all enqueued elements and any associated
     * deduplication/membership tracking. After calling this method, the queue will be empty and
     * previously seen URLs may be enqueued again.
     */
    void init();
}
