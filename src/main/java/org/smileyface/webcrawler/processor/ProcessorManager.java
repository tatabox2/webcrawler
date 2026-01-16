package org.smileyface.webcrawler.processor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.smileyface.webcrawler.crawler.CrawlerProperties;
import org.smileyface.webcrawler.crawler.LinkQueue;
import org.smileyface.webcrawler.elasticsearch.ElasticContext;
import org.smileyface.webcrawler.model.WebPageContent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Manages a set of WebPageProcessor workers running on virtual threads.
 * Provides APIs to start, stop and query statuses of processors.
 */
@Component
public class ProcessorManager {

    private static final Logger log = LogManager.getLogger();

    private final List<WebPageProcessor> processors = new CopyOnWriteArrayList<>();
    private final List<Future<?>> futures = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;
    private final ElasticContext elasticContext;

    /**
     * Autowired constructor providing the ElasticContext bean.
     */
    @Autowired
    public ProcessorManager(ElasticContext elasticContext) {
        this.elasticContext = Objects.requireNonNull(elasticContext, "elasticContext");
        log.info("ElasticContext created: {}", elasticContext);
    }

    /**
     * Default constructor for non-Spring usage (e.g., unit tests creating the manager directly).
     * Falls back to localhost:9200.
     */
    public ProcessorManager() {
        this.elasticContext = new ElasticContext("default", "localhost", 9200);
        log.info("ElasticContext created: {}", elasticContext);
    }

    public synchronized void start(int numWorkers, LinkQueue queue, CrawlerProperties properties,
                                   Consumer<WebPageContent> sink) {
        if (running.get()) {
            throw new IllegalStateException("ProcessorManager already running");
        }
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(sink, "sink");
        int n = Math.max(1, numWorkers);
        processors.clear();
        futures.clear();
        // Create a virtual-thread-per-task executor. Concurrency is controlled by how many tasks we submit (numWorkers).
        executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < n; i++) {
            String id = "proc-" + UUID.randomUUID();
            WebPageProcessor p = new WebPageProcessor(id, queue, properties, sink, elasticContext);
            processors.add(p);
            Future<?> f = executor.submit(p);
            futures.add(f);
        }
        running.set(true);
        log.info("ProcessorManager STARTED with {} workers", n);
    }

    public synchronized void stopAll() {
        for (WebPageProcessor p : processors) {
            p.stop();
        }
        // Wait for all tasks to finish
        for (Future<?> f : futures) {
            try { f.get(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (Exception ignored) { /* task may have errored; status will reflect ERROR */ }
        }
        if (executor != null) {
            executor.shutdown();
        }
        running.set(false);
        logAggregate("STOPPED");
    }

    public List<ProcessorStatus> getStatuses() {
        List<ProcessorStatus> list = new ArrayList<>(processors.size());
        for (WebPageProcessor p : processors) {
            list.add(p.getStatus());
        }
        return list;
    }

    public boolean isRunning() {
        if (!running.get()) return false;
        for (Future<?> f : futures) {
            if (!f.isDone()) return true;
        }
        return false;
    }

    /**
     * Wait until all processors exit or the timeout elapses.
     * @return true if all processors finished before timeout, false otherwise.
     */
    public boolean awaitAll(Duration timeout) {
        long remainingMs = timeout == null ? Long.MAX_VALUE : Math.max(0, timeout.toMillis());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMs);
        for (Future<?> f : futures) {
            long now = System.nanoTime();
            long nanosLeft = deadline - now;
            if (nanosLeft <= 0) return false;
            try {
                f.get(nanosLeft, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                logAggregate("AWAIT TIMEOUT");
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logAggregate("AWAIT INTERRUPTED");
                return false;
            } catch (Exception e) {
                // ExecutionException or others -> treat as finished for await purposes
            }
        }
        if (executor != null) {
            executor.shutdown();
        }
        running.set(false);
        logAggregate("ALL COMPLETED");
        return true;
    }

    private void logAggregate(String event) {
        int completed = 0;
        int stopped = 0;
        int error = 0;
        long processed = 0L;
        List<ProcessorStatus> statuses = getStatuses();
        for (ProcessorStatus s : statuses) {
            processed += s.getProcessedCount();
            ProcessorState st = s.getState();
            if (st == ProcessorState.COMPLETED) completed++;
            else if (st == ProcessorState.STOPPED) stopped++;
            else if (st == ProcessorState.ERROR) error++;
        }
        log.info("ProcessorManager {}: processors -> completed={}, stopped={}, error={}, totalProcessed={} (workers={})",
                event, completed, stopped, error, processed, statuses.size());
    }
}
