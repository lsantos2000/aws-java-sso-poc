package com.example.awssso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.example.awssso.web.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

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
