package org.smileyface.webcrawler.model;

/**
 * High-level crawl outcome/status for a fetched URL/document.
 */
public enum CrawlStatus {
    /** Successfully fetched and parsed. */
    OK,

    /** Skipped due to robots.txt disallow rules. */
    SKIPPED_ROBOTS,

    /** Failed to fetch (network error, timeout, HTTP 5xx, etc.). */
    ERROR_FETCH,

    /** Failed to parse/extract content from the fetched payload. */
    ERROR_PARSE,

    /** Duplicate by URL or content hash. */
    DUPLICATE
}
