package com.example.awssso;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusIsPublicWhenSignedOut() throws Exception {
        mockMvc.perform(get("/api/auth/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void currentUserReturnsClaimsWhenSignedIn() throws Exception {
        OAuth2User user = new DefaultOAuth2User(
            java.util.List.of(() -> "ROLE_USER"),
            java.util.Map.of("sub", "demo-sub", "email", "demo@example.com", "name", "Demo User"),
            "sub");

        mockMvc.perform(get("/api/me").with(oauth2Login().oauth2User(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("demo@example.com"));
    }
}
