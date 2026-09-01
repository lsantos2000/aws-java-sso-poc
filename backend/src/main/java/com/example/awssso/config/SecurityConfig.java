package com.example.awssso.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@Profile("!mock")
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/error", "/actuator/health", "/api/auth/status", "/api/auth/logout", "/oauth2/**", "/login/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/me").authenticated()
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions
                // Everything outside /api keeps the OAuth2 redirect entry point installed by
                // oauth2Login; API callers get a status code they can act on instead of HTML.
                .defaultAuthenticationEntryPointFor(
                    (request, response, authenticationException) ->
                        JsonErrorWriter.write(request, response, 401, "Unauthorized",
                            "Authentication is required. Start the sign-in flow at /oauth2/authorization/cognito."),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    JsonErrorWriter.write(request, response, 403, "Forbidden", "Access is denied for this resource.")))
            .oauth2Login(oauth -> oauth.defaultSuccessUrl("http://localhost:5173", true))
            .logout(logout -> logout
                .logoutSuccessHandler((request, response, authentication) ->
                    response.sendRedirect("http://localhost:5173")));

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
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
