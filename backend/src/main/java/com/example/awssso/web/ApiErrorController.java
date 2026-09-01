package com.example.awssso.web;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring's Whitelabel error page. Registering an {@link ErrorController} makes Boot
 * back off its own, so every error leaves this application as JSON regardless of the
 * {@code Accept} header the caller sent.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<Map<String, Object>> handleError(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", resolveMessage(request, status));
        body.put("path", attribute(request, RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI()));

        return ResponseEntity.status(status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body);
    }

    private HttpStatus resolveStatus(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer statusCode) {
            HttpStatus resolved = HttpStatus.resolve(statusCode);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String resolveMessage(HttpServletRequest request, HttpStatus status) {
        String message = attribute(request, RequestDispatcher.ERROR_MESSAGE, "");
        if (!message.isBlank()) {
            return message;
        }
        if (status == HttpStatus.NOT_FOUND) {
            return "No endpoint matches this path.";
        }
        return "The request could not be completed.";
    }

    private String attribute(HttpServletRequest request, String name, String fallback) {
        Object value = request.getAttribute(name);
        return value == null ? fallback : String.valueOf(value);
    }
}
