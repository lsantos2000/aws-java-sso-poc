package com.example.awssso.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

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
        return Map.of(
            "name", user.getAttribute("name") != null ? user.getAttribute("name") : "AWS user",
            "email", user.getAttribute("email") != null ? user.getAttribute("email") : "",
            "subject", user.getName(),
            "claims", user.getAttributes());
    }
}
