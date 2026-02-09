package org.smileyface.webcrawler.crawler;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Disabled("Consolidated into LinkQueueParameterizedTest")
class InMemoryLinkQueueTest {

    @Test
    void emptyQueueDequeueReturnsNull() {
        InMemoryLinkQueue q = new InMemoryLinkQueue();
        assertThat(q.deQueue()).isNull();
    }

    @Test
    void enqueueNullOrBlankIsIgnored() {
        InMemoryLinkQueue q = new InMemoryLinkQueue();
        q.enqueue(null);
        q.enqueue("   ");
        assertThat(q.deQueue()).isNull();
    }

    @Test
    void deduplicationPreventsDuplicates() {
        InMemoryLinkQueue q = new InMemoryLinkQueue();
        String url = "https://example.com/";

        q.enqueue(url);
        q.enqueue(url); // duplicate

        assertThat(q.deQueue()).isEqualTo(url);
        assertThat(q.deQueue()).isNull(); // only one instance should be present

        // Re-enqueue after dequeue is still ignored due to dedupe set retention
        q.enqueue(url);
        assertThat(q.deQueue()).isNull();
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

        assertThat(q.deQueue()).isEqualTo(a);
        assertThat(q.deQueue()).isEqualTo(b);
        assertThat(q.deQueue()).isEqualTo(c);
        assertThat(q.deQueue()).isNull();
    }
}
