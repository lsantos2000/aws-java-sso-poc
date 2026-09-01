package com.example.awssso.web;

import java.util.List;

import com.example.awssso.logging.LogEvent;
import com.example.awssso.logging.LogStreamService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Feeds the frontend console's BACKEND tab. Registered only when
 * {@code app.log-stream.enabled} is true, which the {@code mock} profile sets.
 */
@RestController
@ConditionalOnProperty(name = "app.log-stream.enabled", havingValue = "true")
public class LogStreamController {

    private final LogStreamService logStream;

    public LogStreamController(LogStreamService logStream) {
        this.logStream = logStream;
    }

    @GetMapping(value = "/api/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return logStream.subscribe();
    }

    /** Polling fallback for clients without EventSource. */
    @GetMapping("/api/logs/recent")
    public List<LogEvent> recent() {
        return logStream.snapshot();
    }
}
