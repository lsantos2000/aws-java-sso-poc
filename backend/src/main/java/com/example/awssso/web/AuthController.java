package com.example.awssso.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuthController {

    /**
     * The claims the UI is allowed to see. Returning {@code user.getAttributes()} wholesale would
     * mean every claim the provider is later configured to release — groups, phone number, custom
     * attributes — reaches the browser with no code change and no review.
     */
    private static final List<String> EXPOSED_CLAIMS = List.of("sub", "name", "email", "email_verified");

    private final String ssoMode;

    public AuthController(@Value("${app.sso-mode:cognito}") String ssoMode) {
        this.ssoMode = ssoMode;
    }

    @GetMapping("/api/auth/status")
    public Map<String, Object> authStatus(@AuthenticationPrincipal OAuth2User user) {
        return Map.of("authenticated", user != null, "mode", ssoMode);
    }

    @GetMapping("/api/me")
    public Map<String, Object> currentUser(@AuthenticationPrincipal OAuth2User user) {
        if (user == null) {
            // Only reachable if the security config lets through a principal that is not an
            // OAuth2User. Answer 401 rather than dereferencing null into a 500.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated OIDC user on this session.");
        }

        Object name = user.getAttribute("name");
        Object email = user.getAttribute("email");

        return Map.of(
            "name", name != null ? name : "AWS user",
            "email", email != null ? email : "",
            "subject", user.getName(),
            "claims", exposedClaims(user));
    }

    private Map<String, Object> exposedClaims(OAuth2User user) {
        Map<String, Object> claims = new LinkedHashMap<>();
        for (String claim : EXPOSED_CLAIMS) {
            Object value = user.getAttribute(claim);
            if (value != null) {
                claims.put(claim, value);
            }
        }
        return claims;
    }
}
