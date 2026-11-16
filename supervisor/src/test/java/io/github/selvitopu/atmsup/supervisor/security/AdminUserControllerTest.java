package io.github.selvitopu.atmsup.supervisor.security;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminUserController.class)
@Import(SecurityConfig.class)
class AdminUserControllerTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AdminUserService admin;

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void aSupervisorCannotSeeTheAccounts() throws Exception {
        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Your role does not allow this"));
    }

    @Test
    @WithMockUser(username = "demo1", roles = "VIEWER")
    void aViewerCannotChangeAnAccount() throws Exception {
        mvc.perform(patch("/api/v1/admin/users/demo1").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(admin);
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void anAdminListsTheAccounts() throws Exception {
        given(admin.list()).willReturn(List.of(new AdminUserDto("demo1", "Demo One", "VIEWER", true)));

        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("demo1"))
                .andExpect(jsonPath("$[0].role").value("VIEWER"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void anAdminChangesARoleAsThemself() throws Exception {
        given(admin.change("demo1", new UserChange("SUPERVISOR", null), "admin1"))
                .willReturn(new AdminUserDto("demo1", "Demo One", "SUPERVISOR", true));

        mvc.perform(patch("/api/v1/admin/users/demo1").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"role\":\"SUPERVISOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPERVISOR"));
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void theirOwnAccountIs409() throws Exception {
        willThrow(new OwnAccountException()).given(admin).change("admin1", new UserChange(null, false), "admin1");

        mvc.perform(patch("/api/v1/admin/users/admin1").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("You cannot change your own account here"));
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void anUnknownUserIs404() throws Exception {
        willThrow(new NoSuchUserException()).given(admin).change("nobody", new UserChange(null, false), "admin1");

        mvc.perform(patch("/api/v1/admin/users/nobody").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No such user"));
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void anAdminSetsAPassword() throws Exception {
        mvc.perform(post("/api/v1/admin/users/demo1/password").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"password\":\"fresh-pass-123\"}"))
                .andExpect(status().isNoContent());

        verify(admin).setPassword("demo1", "fresh-pass-123", "admin1");
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void aBrokenRuleIs400() throws Exception {
        willThrow(new IllegalArgumentException("Roles are ADMIN, SUPERVISOR and VIEWER"))
                .given(admin).change("demo1", new UserChange("ROOT", null), "admin1");

        mvc.perform(patch("/api/v1/admin/users/demo1").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"role\":\"ROOT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Roles are ADMIN, SUPERVISOR and VIEWER"));
    }
}
