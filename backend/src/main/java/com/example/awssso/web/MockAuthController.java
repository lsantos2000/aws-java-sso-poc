package com.example.awssso.web;

import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mock")
public class MockAuthController {

    private static final Logger log = LoggerFactory.getLogger(MockAuthController.class);

    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    @PostConstruct
    void warnThatAuthenticationIsSimulated() {
        log.warn("MOCK SIGN-IN IS ENABLED. POST /api/auth/mock-login creates an authenticated "
            + "session for any caller, with no credentials. This is for local development only — "
            + "never run this profile anywhere reachable by other people.");
    }

    @PostMapping("/api/auth/mock-login")
    public void mockLogin(HttpServletRequest request, HttpServletResponse response) {
        OAuth2User user = new DefaultOAuth2User(
            List.of(() -> "ROLE_USER"),
            Map.of("sub", "mock-user-001", "email", "demo@example.com", "name", "Demo User"),
            "sub");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}