package io.github.selvitopu.atmsup.supervisor.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A signed-out browser receives the CSRF cookie with its very first response, so it can send the token with the
 * sign-in POST. This lives in its own context: spring-security-test's {@code csrf()} post-processor replaces the
 * CSRF token repository of the shared context, which would hide the real cookie from later tests.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class CsrfCookieTest {

    @Autowired
    MockMvc mvc;

    @Test
    void aSignedOutBrowserReceivesTheCsrfCookie() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }
}
