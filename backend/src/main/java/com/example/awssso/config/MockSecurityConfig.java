package com.example.awssso.config;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@Profile("mock")
public class MockSecurityConfig {

    @Bean
    SecurityFilterChain mockSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(mockCorsConfigurationSource()))
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
            .authorizeHttpRequests(auth -> auth
                // /api/logs/** feeds the frontend console. It is open on purpose so the console can
                // show the sign-in handshake before a session exists, and it only exists under this
                // profile (app.log-stream.enabled).
                .requestMatchers("/", "/error", "/actuator/health", "/api/auth/status", "/api/auth/mock-login", "/api/auth/logout", "/api/logs/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/me").authenticated()
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authenticationException) ->
                    JsonErrorWriter.write(request, response, 401, "Unauthorized", unauthorizedMessage(request)))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    JsonErrorWriter.write(request, response, 403, "Forbidden", "Access is denied for this resource.")))
            .logout(logout -> logout.logoutSuccessUrl("http://localhost:5173"));

        return http.build();
    }

    /**
     * Under this profile the OAuth2 login filter is never registered, so a browser that reaches
     * {@code /oauth2/authorization/cognito} would otherwise get a bare 403 with no explanation.
     */
    private static String unauthorizedMessage(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/oauth2/") || path.startsWith("/login/oauth2/")) {
            return "Cognito sign-in is not available because the backend is running the 'mock' profile. "
                + "Use POST /api/auth/mock-login for the local simulator, or restart the backend with the "
                + "'local' profile and Cognito credentials configured.";
        }
        return "Authentication is required. Use POST /api/auth/mock-login to start a local demo session.";
    }

    @Bean
    CorsConfigurationSource mockCorsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}