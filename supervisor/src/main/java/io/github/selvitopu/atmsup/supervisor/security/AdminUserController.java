package io.github.selvitopu.atmsup.supervisor.security;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The accounts, for administrators only. */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService admin;

    public AdminUserController(AdminUserService admin) {
        this.admin = admin;
    }

    @GetMapping
    public List<AdminUserDto> list() {
        return admin.list();
    }

    @PatchMapping("/{username}")
    public AdminUserDto change(@PathVariable String username, @RequestBody UserChange change, Principal principal) {
        return admin.change(username, change, principal.getName());
    }

    @PostMapping("/{username}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPassword(@PathVariable String username, @RequestBody NewPassword body, Principal principal) {
        admin.setPassword(username, body.password(), principal.getName());
    }
}
