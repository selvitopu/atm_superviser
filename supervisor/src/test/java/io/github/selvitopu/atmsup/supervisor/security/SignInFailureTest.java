package io.github.selvitopu.atmsup.supervisor.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A fault behind sign-in, such as the database being down, is not the user's mistake: telling a supervisor that the
 * right password is wrong would send them after the wrong problem in the middle of an outage.
 */
// the filter logs the fault at ERROR, which is right in production and only noise here
@WebMvcTest(controllers = AuthController.class, properties =
        "logging.level.org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter=OFF")
@Import({SecurityConfig.class, SignInFailureTest.BrokenAccounts.class})
class SignInFailureTest {

    @TestConfiguration
    static class BrokenAccounts {
        @Bean
        UserDetailsService users() {
            return username -> {
                throw new DataAccessResourceFailureException("connection refused");
            };
        }
    }

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AccountService accounts;

    @Test
    void aServerFaultAtSignInIsNotReportedAsAWrongPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .param("username", "supervisor1").param("password", "supervisor1-demo"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Sign-in is unavailable right now; try again shortly"));
    }
}
