package org.smileyface.webcrawler.controller;

import org.smileyface.webcrawler.processor.ProcessorStatus;
import org.smileyface.webcrawler.service.CrawlerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/crawler")
public class CrawlerController {

    private final CrawlerService crawlerService;

    public CrawlerController(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    /**
     * Trigger crawler for the given URL. If the URL has already been started previously,
     * return current progress/status instead of triggering again.
     */
    @PostMapping("/trigger")
    public ResponseEntity<ProcessorStatus> triggerCrawler(@RequestParam(name = "url") String url) {
        if (url == null || url.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(null);
        }

        // Decode the URL parameter if it appears to be URL-encoded
        String effectiveUrl = url;
        try {
            if (url.indexOf('%') >= 0 || url.indexOf('+') >= 0) {
                effectiveUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException ex) {
            // Malformed percent-encoding; fall back to original value
            effectiveUrl = url;
        }

        if (crawlerService.hasStarted(effectiveUrl)) {
            ProcessorStatus statuses = crawlerService.getProgressStatusesFor(effectiveUrl);
            return ResponseEntity.ok(statuses);
        }

        boolean started = crawlerService.startCrawlAsync(effectiveUrl);
        if (!started) {
            // Could be invalid URL; surface as 400 with empty list
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(null);
        }
        // Started successfully; return 202 with no statuses yet
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(null);
    }
}
