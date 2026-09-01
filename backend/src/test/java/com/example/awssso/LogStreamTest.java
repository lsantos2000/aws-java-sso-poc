package com.example.awssso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.example.awssso.logging.LogEvent;
import com.example.awssso.logging.LogStreamService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mock")
class LogStreamTest {

    @Autowired
    private LogStreamService logStream;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void backendLogLinesReachTheStream() {
        LoggerFactory.getLogger(LogStreamTest.class).info("handshake marker {}", 42);

        List<LogEvent> events = logStream.snapshot();

        assertTrue(events.stream().anyMatch(event -> "handshake marker 42".equals(event.message())),
            "Logged line should be captured: " + events);
    }

    @Test
    void loggerNamesAreAbbreviatedForTheConsole() {
        LoggerFactory.getLogger("com.example.awssso.web.AuthController").info("abbreviation marker");

        LogEvent event = logStream.snapshot().stream()
            .filter(candidate -> "abbreviation marker".equals(candidate.message()))
            .reduce((first, second) -> second)
            .orElse(null);

        assertNotNull(event, "Expected the marker line to be captured");
        assertEquals("c.e.a.w.AuthController", event.logger());
        assertEquals("INFO", event.level());
    }

    @Test
    void recentEndpointIsReachableWithoutASession() {
        ResponseEntity<List<LogEvent>> response = restTemplate.exchange(
            "/api/logs/recent", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void streamedLinesCarryNoStackTracesOrThreadDetail() {
        LoggerFactory.getLogger(LogStreamTest.class).warn("redaction marker", new IllegalStateException("secret detail"));

        LogEvent event = logStream.snapshot().stream()
            .filter(candidate -> "redaction marker".equals(candidate.message()))
            .reduce((first, second) -> second)
            .orElse(null);

        assertNotNull(event, "Expected the marker line to be captured");
        assertFalse(event.message().contains("secret detail"),
            "Only the formatted message should leave the server, never the throwable");
    }
}
