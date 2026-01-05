package org.smileyface.webcrawler.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Model class representing the content and metadata of a crawled web page.
 * This aligns with the data model described in the README and can be serialized
 * to/from JSON for API responses or persisted in Elasticsearch.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebPageContent {

    // Identity and URL
    private String id;                 // Stable id (e.g., SHA-256 of canonical URL)
    private String url;                // Canonical absolute URL
    private String domain;             // Domain/host of the URL

    // Crawl metadata
    private Long crawlTimestamp;       // Timestamp of the crawl in epoch milliseconds
    private CrawlStatus status;        // Outcome/status of the crawl
    private Integer httpStatus;        // HTTP response status code, if fetched
    private Long fetchDurationMs;      // Fetch latency in milliseconds
    private Integer crawlDepth;        // Depth at which the URL was crawled

    // Content metadata
    private String title;              // HTML <title>
    private String description;        // Meta description or extracted summary
    private List<String> contents;     // Extracted visible text/body segments
    private Integer contentLength;     // Character length of content (or bytes if preferred)
    private String contentType;        // e.g., "text/html"
    private String language;           // Optional language code (e.g., "en")
    private List<String> outLinks;     // Normalized outgoing links from this page

    // Deduplication
    private String hash;               // Hash over URL+content for dedupe

    public WebPageContent() {
        // default
    }

    public WebPageContent(String id, String url, String domain, Long crawlTimestamp, CrawlStatus status,
                           Integer httpStatus, Long fetchDurationMs, Integer crawlDepth, String title,
                           String description, List<String> contents, Integer contentLength, String contentType,
                           String language, List<String> outLinks, String hash) {
        this.id = id;
        this.url = url;
        this.domain = domain;
        this.crawlTimestamp = crawlTimestamp;
        this.status = status;
        this.httpStatus = httpStatus;
        this.fetchDurationMs = fetchDurationMs;
        this.crawlDepth = crawlDepth;
        this.title = title;
        this.description = description;
        this.contents = contents != null ? new ArrayList<>(contents) : null;
        this.contentLength = contentLength;
        this.contentType = contentType;
        this.language = language;
        this.outLinks = outLinks != null ? new ArrayList<>(outLinks) : null;
        // Always derive the hash from URL and content to ensure consistency
        this.hash = computeHash(this.url, this.contents);
    }

    // Getters and setters

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; this.hash = computeHash(this.url, this.contents); }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public Long getCrawlTimestamp() { return crawlTimestamp; }
    public void setCrawlTimestamp(Long crawlTimestamp) { this.crawlTimestamp = crawlTimestamp; }

    public CrawlStatus getStatus() { return status; }
    public void setStatus(CrawlStatus status) { this.status = status; }

    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }

    public Long getFetchDurationMs() { return fetchDurationMs; }
    public void setFetchDurationMs(Long fetchDurationMs) { this.fetchDurationMs = fetchDurationMs; }

    public Integer getCrawlDepth() { return crawlDepth; }
    public void setCrawlDepth(Integer crawlDepth) { this.crawlDepth = crawlDepth; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getContents() { return contents; }
    public void setContents(List<String> contents) {
        this.contents = contents != null ? new ArrayList<>(contents) : null;
        this.hash = computeHash(this.url, this.contents);
    }

    /**
     * Appends a single content segment to the contents list. If the internal list is null,
     * it will be created. The page hash will be recomputed based on the updated contents.
     *
     * @param content a content segment to append (may be null; null will be stored as-is)
     */
    public void addContents(String content) {
        if (this.contents == null) {
            this.contents = new ArrayList<>();
        }
        this.contents.add(content);
        this.hash = computeHash(this.url, this.contents);
    }

    /**
     * Appends all provided content segments to the contents list. If the internal list is null,
     * it will be created. If the provided list is null or empty, this is a no-op. The page hash
     * will be recomputed based on the updated contents.
     *
     * @param moreContents list of content segments to add
     */
    public void addContents(List<String> moreContents) {
        if (moreContents == null || moreContents.isEmpty()) {
            return;
        }
        if (this.contents == null) {
            this.contents = new ArrayList<>();
        }
        this.contents.addAll(moreContents);
        this.hash = computeHash(this.url, this.contents);
    }

    /**
     * Overrides the entire contents list with the provided content, ignoring the index parameter.
     * After this call, the contents list will contain exactly one element: {@code content}
     * (which may be null). The page hash will be recomputed based on the new contents.
     *
     * @param index   ignored parameter kept for backward compatibility
     * @param content the new content value to become the sole element in contents (may be null)
     */
    public void updateContents(int index, String content) {
        List<String> newContents = new ArrayList<>(1);
        newContents.add(content);
        this.contents = newContents;
        this.hash = computeHash(this.url, this.contents);
    }

    /**
     * Replaces the entire contents list with the provided one (defensive copy). Passing null clears
     * the contents. The page hash will be recomputed based on the new contents.
     *
     * @param newContents new list of content segments, or null to clear
     */
    public void updateContents(List<String> newContents) {
        this.contents = newContents != null ? new ArrayList<>(newContents) : null;
        this.hash = computeHash(this.url, this.contents);
    }

    public Integer getContentLength() { return contentLength; }
    public void setContentLength(Integer contentLength) { this.contentLength = contentLength; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public List<String> getOutLinks() { return outLinks; }
    public void setOutLinks(List<String> outLinks) {
        this.outLinks = outLinks != null ? new ArrayList<>(outLinks) : null;
    }

    public String getHash() { return hash; }

    /**
     * Computes a deterministic SHA-256 hex hash from the combination of URL and content.
     * Null inputs are treated as empty strings. A NUL character ('\0') is used as a
     * separator to avoid ambiguity between different (url, content) pairs.
     */
    public static String computeHash(String url, String content) {
        String u = url == null ? "" : url;
        String c = content == null ? "" : content;
        byte[] data = (u + '\0' + c).getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to exist on all Java platforms
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Computes a deterministic SHA-256 hex hash from the combination of URL and list of content segments.
     * Null inputs are treated as empty. A NUL character ('\0') is used as a separator between URL and contents,
     * and a unit separator ('\u001F') is used between content segments to avoid ambiguity.
     */
    public static String computeHash(String url, List<String> contents) {
        String u = url == null ? "" : url;
        String joined;
        if (contents == null || contents.isEmpty()) {
            joined = "";
        } else {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (String part : contents) {
                if (!first) sb.append('\u001F'); // unit separator between parts
                sb.append(part == null ? "" : part);
                first = false;
            }
            joined = sb.toString();
        }
        byte[] data = (u + '\0' + joined).getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebPageContent that = (WebPageContent) o;
        // Prefer id if available, otherwise fall back to URL for identity
        if (this.id != null || that.id != null) {
            return Objects.equals(this.id, that.id);
        }
        return Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() {
        return (id != null) ? Objects.hash(id) : Objects.hash(url);
    }

    @Override
    public String toString() {
        return "WebPageContent{" +
                "id='" + id + '\'' +
                ", url='" + url + '\'' +
                ", domain='" + domain + '\'' +
                ", crawlTimestamp=" + crawlTimestamp +
                ", status=" + status +
                ", httpStatus=" + httpStatus +
                ", title='" + title + '\'' +
                ", contentLength=" + contentLength +
                ", contentType='" + contentType + '\'' +
                ", language='" + language + '\'' +
                ", outLinks=" + (outLinks != null ? outLinks.size() : 0) +
                ", hash='" + hash + '\'' +
                ", fetchDurationMs=" + fetchDurationMs +
                ", crawlDepth=" + crawlDepth +
                '}';
    }
}
