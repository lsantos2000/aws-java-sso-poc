package com.example.awssso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mock")
class MockSsoE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void mockLoginCreatesSessionThatCanAccessCurrentUser() {
        ResponseEntity<Void> loginResponse = restTemplate.postForEntity(
            UriComponentsBuilder.fromPath("/api/auth/mock-login").build().toUri(),
            null,
            Void.class);

        assertEquals(HttpStatus.OK, loginResponse.getStatusCode());
        String sessionCookie = sessionCookie(loginResponse);

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.add(HttpHeaders.COOKIE, sessionCookie);
        ResponseEntity<Map<String, Object>> userResponse = restTemplate.exchange(
            "/api/me",
            HttpMethod.GET,
            new HttpEntity<>(requestHeaders),
            new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, userResponse.getStatusCode());
        assertNotNull(userResponse.getBody());
        assertEquals("Demo User", userResponse.getBody().get("name"));
        assertEquals("demo@example.com", userResponse.getBody().get("email"));
        assertEquals("mock-user-001", userResponse.getBody().get("subject"));
    }

    @Test
    void protectedUserEndpointRejectsRequestsWithoutSession() {
        ResponseEntity<String> userResponse = restTemplate.getForEntity("/api/me", String.class);

        assertTrue(List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN).contains(userResponse.getStatusCode()));
    }

    private String sessionCookie(ResponseEntity<?> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "Mock login should create a session cookie");
        return setCookie.substring(0, setCookie.indexOf(';'));
    }
}
