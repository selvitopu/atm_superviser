package io.github.selvitopu.atmsup.supervisor.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = AuthController.class, properties = "atm.demo-mode=true")
@Import(SecurityConfig.class)
class AuthControllerTest {

    private static final String BODY = "{\"username\":\"newuser\",\"displayName\":\"New User\",\"password\":\"long-enough-pw\"}";

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AccountService accounts;

    @Test
    void registerIsOpenToAnonymousUsersButNeedsCsrf() throws Exception {
        given(accounts.register(any())).willReturn(new UserDto("newuser", "New User", List.of("VIEWER")));

        mvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newuser"))
                .andExpect(jsonPath("$.roles[0]").value("VIEWER"));
    }

    @Test
    void aTakenNameIs409() throws Exception {
        given(accounts.register(any())).willThrow(new UsernameTakenException());

        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That username is taken"));
    }

    @Test
    void anInvalidRequestIs400WithTheRule() throws Exception {
        given(accounts.register(any())).willThrow(new IllegalArgumentException("Passwords are 10 to 72 characters, and accented letters count double"));

        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Passwords are 10 to 72 characters, and accented letters count double"));
    }

    @Test
    void closedRegistrationIs403() throws Exception {
        given(accounts.register(any())).willThrow(new RegistrationClosedException());

        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Registration is switched off"));
    }

    @Test
    void configListsTheDemoAccountsInDemoMode() throws Exception {
        given(accounts.registrationEnabled()).willReturn(true);

        mvc.perform(get("/api/v1/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoMode").value(true))
                .andExpect(jsonPath("$.registrationEnabled").value(true))
                .andExpect(jsonPath("$.demoAccounts.length()").value(3))
                .andExpect(jsonPath("$.demoAccounts[0].username").value("admin1"))
                .andExpect(jsonPath("$.demoAccounts[1].username").value("supervisor1"))
                .andExpect(jsonPath("$.demoAccounts[2].username").value("demo1"))
                .andExpect(jsonPath("$.demoAccounts[1].password").value("supervisor1-demo"));
    }

    @Nested
    @TestPropertySource(properties = "atm.demo-mode=false")
    class OutsideDemoMode {

        @Test
        void configListsNoDemoAccounts() throws Exception {
            mvc.perform(get("/api/v1/auth/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.demoMode").value(false))
                    .andExpect(jsonPath("$.demoAccounts.length()").value(0));
        }
    }
}
