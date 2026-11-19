package io.github.selvitopu.atmsup.supervisor.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account administration: one role per account, enabling and disabling, and setting a new password. Every change ends
 * the account's live sessions as soon as it is committed, so a new role or a disabled account takes effect at once.
 * An administrator cannot change their own account here: that could lock the last administrator out or sign them out
 * mid-change.
 */
@Service
public class AdminUserService {

    /** From the most to the least privileged, the order the role hierarchy grants them in. */
    static final List<String> ROLES = List.of("ADMIN", "SUPERVISOR", "VIEWER");

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final LiveSessions sessions;

    public AdminUserService(AppUserRepository users, PasswordEncoder passwords, LiveSessions sessions) {
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public List<AdminUserDto> list() {
        return users.findAll(Sort.by("username")).stream().map(AdminUserService::dto).toList();
    }

    @Transactional
    public AdminUserDto change(String username, UserChange change, String actor) {
        AppUser user = editable(username, actor);
        List<String> changes = new ArrayList<>();
        if (change.role() != null) {
            if (!ROLES.contains(change.role())) {
                throw new IllegalArgumentException("Roles are ADMIN, SUPERVISOR and VIEWER");
            }
            String old = role(user);
            if (!old.equals(change.role())) {
                user.changeRole(change.role());
                changes.add("role " + old + " → " + change.role());
            }
        }
        if (change.enabled() != null && change.enabled() != user.isEnabled()) {
            user.setEnabled(change.enabled());
            changes.add(change.enabled() ? "enabled" : "disabled");
        }
        if (!changes.isEmpty()) {
            users.flush();
            endSessionsAfterCommit(user.getUsername());
            log.info("{} changed {}: {}", actor, user.getUsername(), String.join(", ", changes));
        }
        return dto(user);
    }

    @Transactional
    public void setPassword(String username, String password, String actor) {
        AppUser user = editable(username, actor);
        AccountService.checkPassword(user.getUsername(), password);
        user.changePasswordHash(passwords.encode(password));
        users.flush();
        endSessionsAfterCommit(user.getUsername());
        log.info("{} set a new password for {}", actor, user.getUsername());
    }

    /**
     * Ends the account's sessions once the change is committed. Ended earlier, a sign-in completing before the commit
     * would read the old role or enabled flag and keep its session; one that reads the account before the commit and
     * stores its session after this runs still can, which only a check of the account on every request would close.
     */
    private void endSessionsAfterCommit(String username) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessions.endSessionsOf(username);
            }
        });
    }

    private AppUser editable(String username, String actor) {
        String name = AccountDetailsService.normalise(username);
        AppUser user = users.findByUsername(name).orElseThrow(NoSuchUserException::new);
        if (user.getUsername().equals(actor)) {
            throw new OwnAccountException();
        }
        return user;
    }

    /** The account's highest role; the screen manages exactly one. */
    private static String role(AppUser user) {
        return user.getRoles().stream().min(Comparator.comparingInt(ROLES::indexOf)).orElse("VIEWER");
    }

    private static AdminUserDto dto(AppUser user) {
        return new AdminUserDto(user.getUsername(), user.getDisplayName(), role(user), user.isEnabled());
    }
}
