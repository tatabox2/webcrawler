package org.smileyface.webcrawler.processor;

import java.time.Instant;

/**
 * Immutable snapshot of a processor's status.
 */
public final class ProcessorStatus {
    private final String id;
    private final ProcessorState state;
    private final long processedCount;
    private final String lastUrl;
    private final String lastError;
    private final Instant startedAt;
    private final Instant finishedAt;

    public ProcessorStatus(String id, ProcessorState state, long processedCount, String lastUrl, String lastError,
                           Instant startedAt, Instant finishedAt) {
        this.id = id;
        this.state = state;
        this.processedCount = processedCount;
        this.lastUrl = lastUrl;
        this.lastError = lastError;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    public String getId() { return id; }
    public ProcessorState getState() { return state; }
    public long getProcessedCount() { return processedCount; }
    public String getLastUrl() { return lastUrl; }
    public String getLastError() { return lastError; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
