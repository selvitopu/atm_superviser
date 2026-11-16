package io.github.selvitopu.atmsup.supervisor.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A signed-out browser receives the CSRF cookie with its very first response, so it can send the token with the
 * sign-in POST. This lives in its own context: spring-security-test's {@code csrf()} post-processor replaces the
 * CSRF token repository of the shared context, which would hide the real cookie from later tests.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, CsrfCookieTest.Users.class})
class CsrfCookieTest {

    @TestConfiguration
    static class Users {
        @Bean
        UserDetailsService users() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("supervisor1").password("{noop}supervisor1-demo").roles("SUPERVISOR").build());
        }
    }

    @Autowired
    MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    AccountService accounts;

    @Test
    void aSignedOutBrowserReceivesTheCsrfCookie() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    void signInHandsBackAFreshCsrfToken() throws Exception {
        Cookie token = mvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        List<String> setCookies = mvc.perform(post("/api/v1/auth/login").cookie(token)
                        .header("X-XSRF-TOKEN", token.getValue())
                        .param("username", "supervisor1").param("password", "supervisor1-demo"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeaders("Set-Cookie");

        // sign-in rotates the token: the response deletes the old cookie, then sets the new one, which a browser
        // applies in that order; the next POST must not go out without a token
        String last = setCookies.stream().filter(c -> c.startsWith("XSRF-TOKEN=")).reduce((a, b) -> b).orElseThrow();
        String value = last.substring("XSRF-TOKEN=".length(), last.indexOf(';'));
        assertThat(value).isNotEmpty().isNotEqualTo(token.getValue());
    }
}
