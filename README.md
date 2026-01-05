WebCrawler Spring Boot Application — Implementation Plan

Overview
This document outlines a pragmatic, incremental plan to build a Spring Boot application that crawls website content, indexes it into Elasticsearch, and exposes REST endpoints to search and inspect the indexed content.

Goals
- Crawl public web pages starting from one or more seed URLs, respecting robots.txt and politeness rules.
- Extract and normalize textual content and key metadata.
- Index documents into Elasticsearch for fast, full‑text search.
- Provide REST endpoints to trigger crawling and to search/query the indexed content.
- Ship as a self‑contained Spring Boot service with observability and configuration.

High‑Level Architecture
- API Layer (Spring MVC)
  - CrawlController: endpoints to enqueue seeds, manage crawl jobs.
  - SearchController: endpoints to query/search and fetch individual docs.
  - Admin/StatusController: health, metrics, and counters.
- Crawler Subsystem
  - Scheduler: triggers periodic crawl waves and background workers (@Scheduled).
  - Frontier/Queue: URL queue with deduplication and prioritization.
  - Fetcher: HTTP client with rate limiting, retries, timeouts, and robots.txt compliance.
  - Parser: HTML parsing (Jsoup) to extract visible text, title, meta, links.
  - Normalizer: URL canonicalization, content cleanup (e.g., CrawlerUtils.removeHtmlTags), language detection (optional), and hashing for dedupe.
  - Sitemap resolver: optional discovery via robots.txt and sitemap.xml when available.
- Indexing Subsystem
  - Indexer: transforms parsed page into an Elasticsearch document and writes via bulk API.
  - Dead‑letter handling: store failures for later reprocessing.
- Storage
  - Elasticsearch cluster (single node acceptable for local dev/test).

Key Technology Choices
- Spring Boot 4.x (already present)
- Spring Web MVC (already present)
- Jsoup for HTML parsing and text extraction
- Elasticsearch Java API Client (co.elastic.clients:elasticsearch-java)
- Spring Scheduling (@EnableScheduling)
- Resilience4j (optional) for circuit breakers/retries
- Testcontainers for integration tests with Elasticsearch

Gradle Dependencies (to be added when implementing)
- implementation 'org.jsoup:jsoup:1.18.1'
- implementation 'co.elastic.clients:elasticsearch-java:8.15.3'
- implementation 'org.springframework.boot:spring-boot-starter-validation'
- implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0' (optional)
- testImplementation 'org.testcontainers:elasticsearch:1.20.4'

Configuration (application.yaml)
crawler:
  seeds: ["https://example.com"]
  maxDepth: 2
  maxPages: 1000
  userAgent: "SmileyfaceBot/1.0 (+https://yourdomain.example/bot)"
  requestTimeoutMs: 8000
  socketTimeoutMs: 15000
  maxConcurrentPerDomain: 2
  globalQps: 5
  politenessDelayMs: 2000
  obeyRobotsTxt: true
  followSitemaps: true
  allowedContentTypes: ["text/html"]
elasticsearch:
  hosts: ["http://localhost:9200"]
  indexName: "web_pages"
  username: ""
  password: ""

Data Model (Elasticsearch Document)
- id: string (stable id, e.g., SHA-256 of canonical URL)
- url: string
- domain: keyword
- crawlTimestamp: date
- status: keyword (OK, SKIPPED_ROBOTS, ERROR_FETCH, ERROR_PARSE, DUPLICATE, etc.)
- httpStatus: integer
- title: text + keyword subfield
- description: text
- content: text (main visible body)
- contentLength: integer
- contentType: keyword
- language: keyword (optional)
- outLinks: keyword[] (normalized URLs)
- hash: keyword (content hash for dedupe)
- fetchDurationMs: long
- crawlDepth: integer

Example ES Index Mapping (simplified)
PUT web_pages
{
  "settings": {
    "analysis": {
      "analyzer": {
        "english_html": {"type": "standard"}
      }
    }
  },
  "mappings": {
    "properties": {
      "url": {"type": "keyword"},
      "domain": {"type": "keyword"},
      "crawlTimestamp": {"type": "date"},
      "status": {"type": "keyword"},
      "httpStatus": {"type": "integer"},
      "title": {"type": "text", "fields": {"raw": {"type": "keyword", "ignore_above": 256}}},
      "description": {"type": "text"},
      "content": {"type": "text"},
      "language": {"type": "keyword"},
      "outLinks": {"type": "keyword"},
      "hash": {"type": "keyword"},
      "fetchDurationMs": {"type": "long"},
      "crawlDepth": {"type": "integer"}
    }
  }
}

REST API Design
- Crawl APIs
  - POST /api/crawl
    - Body: { "seeds": ["https://example.com", ...], "maxDepth": 2, "maxPages": 100 }
    - Effect: enqueue seeds and start/trigger background crawl job.
    - Response: { "jobId": "uuid", "enqueued": N }
  - GET /api/crawl/{jobId}
    - Returns job status: queued, running, completed, failed, with counters.

- Search APIs
  - GET /api/search?q=term&page=0&size=10&domain=example.com
    - Response: {
        "total": 123,
        "page": 0,
        "size": 10,
        "hits": [{ "id": "...", "url": "...", "title": "...", "snippet": "...", "score": 1.23 }]
      }
  - GET /api/doc/{id}
    - Returns full stored document by id (or by url via /api/doc?url=...).

- Admin/Status
  - GET /api/status/metrics — crawl rates, queue depth, success/error counts.
  - GET /actuator/health — Spring Boot health; include ES and queue health indicators.

Crawling Strategy
- Seeding
  - Accept seeds via API and via config. Store in an in‑memory queue (MVP) or persistent queue (future: Redis/Kafka).
- Frontier Management
  - Maintain a visited set keyed by canonical URL; compute id = hash(canonicalUrl).
  - Per‑domain politeness and rate limits; global QPS limit.
  - Breadth‑first within domain; cap depth and total pages per job.
- Robots and Sitemaps
  - Fetch and cache robots.txt per domain; honor disallow/allow and crawl‑delay.
  - Optionally discover sitemaps and enqueue entries up to limits.
- Fetching
  - Use HttpClient (Java 11+ or Apache HttpClient) with timeouts, gzip, conditional GETs (ETag/If‑Modified‑Since) when available.
  - Retry on transient 5xx with exponential backoff; do not retry on 4xx except 429 with Retry‑After.
- Parsing and Extraction
  - Use Jsoup to parse HTML; discard scripts/styles; extract title, meta description, canonical link, and visible text.
  - Normalize whitespace; use CrawlerUtils.removeHtmlTags as a fallback sanitizer.
  - Extract and normalize outgoing links; enqueue same‑site links by default (configurable).
- Deduplication
  - Skip if content hash unchanged (store last hash in ES) or URL already visited.

Indexing Pipeline
- Transform parsed page to ES document model.
- Use bulk indexing with backpressure (flush on size/time).
- On failure, log and send to a dead‑letter list for retry.

Scheduling and Concurrency
- Enable @EnableScheduling; run small worker pool (e.g., ThreadPoolTaskExecutor) processing the frontier.
- Respect per‑domain concurrency constraints; track last‑fetch time per domain.

Observability
- Structured logging with MDC (jobId, domain).
- Metrics via Micrometer: fetch latency, success rate, queue depth, ES bulk timings.
- Tracing (optional) with OpenTelemetry.

Security and Safety
- Validate and whitelist seed domains (optional) to avoid crawling the entire web accidentally.
- Input validation on APIs (javax.validation annotations).
- CORS configurable for UI consumers.
- Rate limit public endpoints to prevent abuse.

Testing Strategy
- Unit tests for utilities (e.g., CrawlerUtils — already present).
- Unit tests for URL normalization, robots parsing, parser extraction.
- Integration tests with Testcontainers Elasticsearch to verify indexing and search endpoints.
- Contract tests for REST controllers with MockMvc.

MVP Milestones
1. Skeleton project with endpoints and a no‑op in‑memory queue; parse with Jsoup; index to ES.
2. Respect robots.txt and per‑domain politeness; add simple scheduler.
3. Add bulk indexing, search endpoint with highlighting/snippets.
4. Add sitemaps discovery; improve dedupe and normalization.

Local Development
1. Start Elasticsearch locally (Docker):
   docker run -p 9200:9200 -e discovery.type=single-node -e xpack.security.enabled=false docker.elastic.co/elasticsearch/elasticsearch:8.15.3
2. Configure application.yaml per above.
3. Run the app: ./gradlew bootRun
4. Enqueue a crawl:
   curl -XPOST localhost:8080/api/crawl -H 'Content-Type: application/json' -d '{"seeds":["https://example.com"],"maxDepth":1}'
5. Search:
   curl 'localhost:8080/api/search?q=example&size=5'

Future Enhancements
- Persistent frontier (Redis/Kafka), distributed crawling.
- Language detection and per‑language analyzers.
- Duplicate detection via SimHash or MinHash.
- Boilerplate removal (Readability/Boilerpipe‑like heuristics) for better text.
- UI for monitoring and search.

Notes
- Keep an eye on legal and ethical crawling practices; identify as a bot in User‑Agent.
- Implement domain blacklists/whitelists and max content size limits.

---

FAQ: ConcurrentLinkedQueue vs HashSet in Java

When should you use ConcurrentLinkedQueue and when should you use HashSet (or a concurrent set) — and why do we use both in this project?

- Purpose
  - ConcurrentLinkedQueue: an unbounded, lock‑free, thread‑safe FIFO queue for handing off work between producers and consumers. It preserves insertion order.
  - HashSet: a collection modeling membership/uniqueness with no ordering guarantees. A plain HashSet is not thread‑safe.

- Thread‑safety
  - ConcurrentLinkedQueue is designed for high‑concurrency scenarios without external synchronization.
  - HashSet is not thread‑safe; for concurrent usage you need synchronization or a concurrent alternative (e.g., Collections.synchronizedSet, ConcurrentHashMap.newKeySet()).

- Ordering and semantics
  - Queue: dequeue order ≈ enqueue order (FIFO). Suitable for work queues/frontiers.
  - Set: no duplicates; order is unspecified (or insertion order only in LinkedHashSet). Suitable for deduplication/membership tests.

- Null handling
  - ConcurrentLinkedQueue disallows null elements.
  - HashSet allows null (one), but most concurrent set implementations based on ConcurrentHashMap (e.g., ConcurrentHashMap.newKeySet()) disallow null.

- Performance characteristics
  - ConcurrentLinkedQueue offers non‑blocking operations with good throughput under contention; operations are O(1) amortized.
  - HashSet operations are average O(1), but require external synchronization for concurrent access, which can become a bottleneck. ConcurrentHashMap.newKeySet() provides scalable, lock‑striped concurrency for membership checks.

- Typical use cases
  - Use ConcurrentLinkedQueue for task/work items that must be processed in order.
  - Use a Set for "have we seen this?" checks to prevent duplicates.

How we apply this here
- InMemoryLinkQueue uses ConcurrentLinkedQueue for the crawl frontier (processing order) and a concurrent set (ConcurrentHashMap.newKeySet()) to deduplicate enqueues. This combination delivers FIFO processing with uniqueness and safe concurrent access.
