package com.example.awssso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.example.awssso.web.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

class AuthControllerUnitTest {

    private final AuthController controller = new AuthController("mock");

    @Test
    void authStatusReportsMockModeWhenSignedOut() {
        Map<String, Object> response = controller.authStatus(null);

        assertFalse((Boolean) response.get("authenticated"));
        assertEquals("mock", response.get("mode"));
    }

    @Test
    void authStatusReportsAuthenticatedWhenUserExists() {
        OAuth2User user = demoUser();

        Map<String, Object> response = controller.authStatus(user);

        assertTrue((Boolean) response.get("authenticated"));
        assertEquals("mock", response.get("mode"));
    }

    @Test
    void currentUserMapsIdentityClaims() {
        Map<String, Object> response = controller.currentUser(demoUser());

        assertEquals("Demo User", response.get("name"));
        assertEquals("demo@example.com", response.get("email"));
        assertEquals("mock-user-001", response.get("subject"));
        assertEquals("demo@example.com", ((Map<?, ?>) response.get("claims")).get("email"));
    }

    @Test
    void currentUserExposesOnlyWhitelistedClaims() {
        OAuth2User user = new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_USER")),
            Map.of(
                "sub", "mock-user-001",
                "email", "demo@example.com",
                "custom:salary", "120000",
                "phone_number", "+15555550123"),
            "sub");

        Map<?, ?> claims = (Map<?, ?>) controller.currentUser(user).get("claims");

        assertEquals("mock-user-001", claims.get("sub"));
        assertEquals("demo@example.com", claims.get("email"));
        assertFalse(claims.containsKey("custom:salary"), "Unlisted claims must not reach the browser");
        assertFalse(claims.containsKey("phone_number"), "Unlisted claims must not reach the browser");
    }

    @Test
    void currentUserRejectsAMissingPrincipalRatherThanFailingWithNull() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> controller.currentUser(null));

        assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatusCode());
    }

    @Test
    void currentUserUsesFallbackNameAndEmail() {
        OAuth2User user = new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_USER")),
            Map.of("sub", "subject-only"),
            "sub");

        Map<String, Object> response = controller.currentUser(user);

        assertEquals("AWS user", response.get("name"));
        assertEquals("", response.get("email"));
        assertEquals("subject-only", response.get("subject"));
    }

    private OAuth2User demoUser() {
        return new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_USER")),
            Map.of("sub", "mock-user-001", "email", "demo@example.com", "name", "Demo User"),
            "sub");
    }
}
