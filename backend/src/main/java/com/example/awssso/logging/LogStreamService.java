package com.example.awssso.logging;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Attaches to the Logback root logger and forwards each line to subscribed browsers, so the
 * frontend console can show what the backend is doing during a sign-in.
 *
 * <p>This publishes server logs to anyone who can reach the endpoint, so it is gated behind
 * {@code app.log-stream.enabled} and only switched on by the {@code mock} profile.
 */
@Service
@ConditionalOnProperty(name = "app.log-stream.enabled", havingValue = "true")
public class LogStreamService {

    private static final int HISTORY_LIMIT = 150;
    private static final long STREAM_TIMEOUT_MS = 3_600_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Deque<LogEvent> history = new ArrayDeque<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * Writing to a dead connection makes Tomcat log, which would re-enter this appender and
     * fail again. One flag per thread breaks that cycle.
     */
    private final ThreadLocal<Boolean> publishing = ThreadLocal.withInitial(() -> false);

    private Forwarder forwarder;

    @PostConstruct
    void attachToRootLogger() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        forwarder = new Forwarder();
        forwarder.setContext(context);
        forwarder.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(forwarder);
    }

    @PreDestroy
    void detachFromRootLogger() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(forwarder);
        forwarder.stop();
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));

        // Replay what already happened so a browser opening the console mid-run sees context.
        for (LogEvent event : snapshot()) {
            if (!send(emitter, event)) {
                return emitter;
            }
        }

        emitters.add(emitter);
        return emitter;
    }

    public List<LogEvent> snapshot() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    private void publish(LogEvent event) {
        synchronized (history) {
            history.addLast(event);
            while (history.size() > HISTORY_LIMIT) {
                history.removeFirst();
            }
        }
        for (SseEmitter emitter : emitters) {
            if (!send(emitter, event)) {
                emitters.remove(emitter);
            }
        }
    }

    private boolean send(SseEmitter emitter, LogEvent event) {
        try {
            emitter.send(SseEmitter.event().name("log").data(mapper.writeValueAsString(event), MediaType.TEXT_PLAIN));
            return true;
        } catch (IOException | IllegalStateException failure) {
            return false;
        }
    }

    private LogEvent toLogEvent(ILoggingEvent event) {
        return new LogEvent(
            LocalTime.now().format(TIME),
            event.getLevel().toString(),
            shortenLoggerName(event.getLoggerName()),
            event.getFormattedMessage());
    }

    /** {@code com.example.awssso.web.AuthController} reads as {@code c.e.a.w.AuthController}. */
    private String shortenLoggerName(String name) {
        int lastDot = name.lastIndexOf('.');
        if (lastDot < 0) {
            return name;
        }
        StringBuilder shortened = new StringBuilder();
        for (String part : name.substring(0, lastDot).split("\\.")) {
            if (!part.isEmpty()) {
                shortened.append(part.charAt(0)).append('.');
            }
        }
        return shortened.append(name.substring(lastDot + 1)).toString();
    }

    private final class Forwarder extends AppenderBase<ILoggingEvent> {

        @Override
        protected void append(ILoggingEvent event) {
            if (Boolean.TRUE.equals(publishing.get())) {
                return;
            }
            publishing.set(true);
            try {
                publish(toLogEvent(event));
            } finally {
                publishing.set(false);
            }
        }
    }
}
