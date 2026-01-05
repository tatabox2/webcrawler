package org.smileyface.webcrawler.processor;

/**
 * Lifecycle state of a WebPageProcessor.
 */
public enum ProcessorState {
    NEW,
    RUNNING,
    STOPPED,
    COMPLETED,
    ERROR
}
