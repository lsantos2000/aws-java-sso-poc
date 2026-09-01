package com.example.awssso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * A browser sends {@code Accept: text/html}, which is what made Spring serve the Whitelabel
 * error page. These tests pin the JSON contract for exactly that request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mock")
class ErrorResponseTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void cognitoLoginUnderMockProfileExplainsItselfAsJson() {
        ResponseEntity<Map<String, Object>> response = getAsBrowser("/oauth2/authorization/cognito");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertJsonContentType(response);
        assertNotNull(response.getBody());
        assertEquals(401, response.getBody().get("status"));
        assertEquals("/oauth2/authorization/cognito", response.getBody().get("path"));

        String message = String.valueOf(response.getBody().get("message"));
        assertTrue(message.contains("mock"), "Message should name the active profile: " + message);
        assertTrue(message.contains("/api/auth/mock-login"), "Message should point at the working route: " + message);
    }

    @Test
    void protectedEndpointReturnsJsonRatherThanWhitelabelHtml() {
        ResponseEntity<String> response = restTemplate.exchange(
            "/api/me", HttpMethod.GET, new HttpEntity<>(browserHeaders()), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().contains("Whitelabel"), "Whitelabel page should be gone");
        assertTrue(response.getBody().startsWith("{"), "Body should be JSON: " + response.getBody());
    }

    @Test
    void anonymousRequestToAnUnknownPathIsRejectedBeforeRouting() {
        // anyRequest().authenticated() means the security filter chain answers first, so an
        // unknown path never reaches the dispatcher and cannot 404. That also keeps the app
        // from advertising which paths exist.
        ResponseEntity<Map<String, Object>> response = getAsBrowser("/does-not-exist");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertJsonContentType(response);
    }

    @Test
    void unknownPathReturnsJsonNotFoundForASignedInCaller() {
        ResponseEntity<Void> login = restTemplate.postForEntity("/api/auth/mock-login", null, Void.class);
        String setCookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "Mock login should create a session cookie");

        HttpHeaders headers = browserHeaders();
        headers.add(HttpHeaders.COOKIE, setCookie.substring(0, setCookie.indexOf(';')));
        ResponseEntity<String> response = restTemplate.exchange(
            "/does-not-exist", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertJsonContentType(response);
        assertNotNull(response.getBody());
        assertFalse(response.getBody().contains("Whitelabel"), "Whitelabel page should be gone");
        assertTrue(response.getBody().contains("\"status\":404"), "Body should be JSON: " + response.getBody());
    }

    private ResponseEntity<Map<String, Object>> getAsBrowser(String path) {
        return restTemplate.exchange(
            path, HttpMethod.GET, new HttpEntity<>(browserHeaders()), new ParameterizedTypeReference<>() {});
    }

    private HttpHeaders browserHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.TEXT_HTML, MediaType.ALL));
        return headers;
    }

    private void assertJsonContentType(ResponseEntity<?> response) {
        MediaType contentType = response.getHeaders().getContentType();
        assertNotNull(contentType, "Missing Content-Type");
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(contentType), "Expected JSON, got " + contentType);
    }
}
