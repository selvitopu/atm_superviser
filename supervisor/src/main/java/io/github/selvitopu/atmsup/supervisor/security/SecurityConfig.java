package io.github.selvitopu.atmsup.supervisor.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Session sign-in for the single-page UI. The session cookie carries the sign-in, and CSRF protection uses the
 * {@code XSRF-TOKEN} cookie that Angular sends back as {@code X-XSRF-TOKEN}. Every {@code /api} call needs a signed-in
 * user except signing in, registering, the sign-in configuration and the API documentation; failures are RFC 9457
 * problem details, never redirects. Roles form the hierarchy ADMIN > SUPERVISOR > VIEWER.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN").implies("SUPERVISOR")
                .role("SUPERVISOR").implies("VIEWER")
                .build();
    }

    @Bean
    static PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
        http
                .csrf(csrf -> csrf.spa())
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .authorizeHttpRequests(requests -> requests
                        // an async dispatch (the live stream) continues a request that was already authorized; after
                        // a sign-out its session is gone, so authorizing it again would fail into a committed response
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/config", "/api/v1/auth/register",
                                "/api/v1/openapi.json", "/api/v1/openapi.json/**", "/api/v1/docs", "/api/v1/docs/**",
                                "/api/v1/swagger-ui/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(login -> login
                        .loginProcessingUrl("/api/v1/auth/login")
                        .successHandler((request, response, authentication) ->
                                write(response, json, HttpStatus.OK, MediaType.APPLICATION_JSON_VALUE,
                                        UserDto.of(authentication)))
                        // a fault behind sign-in (the database down) is not a wrong password
                        .failureHandler((request, response, exception) -> {
                            if (exception instanceof AuthenticationServiceException) {
                                problem(response, json, HttpStatus.SERVICE_UNAVAILABLE,
                                        "Sign-in is unavailable right now; try again shortly");
                            } else {
                                problem(response, json, HttpStatus.UNAUTHORIZED, "Wrong username or password");
                            }
                        }))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                problem(response, json, HttpStatus.UNAUTHORIZED, "Sign in to continue"))
                        .accessDeniedHandler((request, response, exception) ->
                                problem(response, json, HttpStatus.FORBIDDEN, exception instanceof CsrfException
                                        ? "The request has no valid security token; reload the page"
                                        : "Your role does not allow this")))
                .httpBasic(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private static void problem(HttpServletResponse response, JsonMapper json, HttpStatus status, String detail)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        write(response, json, status, MediaType.APPLICATION_PROBLEM_JSON_VALUE, body);
    }

    private static void write(HttpServletResponse response, JsonMapper json, HttpStatus status, String contentType,
                              Object body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(contentType);
        json.writeValue(response.getOutputStream(), body);
    }
}
