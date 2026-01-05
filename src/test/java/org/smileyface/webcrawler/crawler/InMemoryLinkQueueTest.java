package org.smileyface.webcrawler.crawler;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Disabled("Consolidated into LinkQueueParameterizedTest")
class InMemoryLinkQueueTest {

    @Test
    void emptyQueueDequeueReturnsNull() {
        InMemoryLinkQueue q = new InMemoryLinkQueue();
        assertNull(q.deQueue());
    }

    @Test
    void enqueueNullOrBlankIsIgnored() {
        InMemoryLinkQueue q = new InMemoryLinkQueue();
        q.enqueue(null);
        q.enqueue("   ");
        assertNull(q.deQueue());
    }

    @Test
    void deduplicationPreventsDuplicates() {
        InMemoryLinkQueue q = new InMemoryLinkQueue();
        String url = "https://example.com/";

        q.enqueue(url);
        q.enqueue(url); // duplicate

        assertEquals(url, q.deQueue());
        assertNull(q.deQueue()); // only one instance should be present

        // Re-enqueue after dequeue is still ignored due to dedupe set retention
        q.enqueue(url);
        assertNull(q.deQueue());
    }

    @Test
    void fifoOrderWithDeduplication() {
        InMemoryLinkQueue q = new InMemoryLinkQueue();
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
}
