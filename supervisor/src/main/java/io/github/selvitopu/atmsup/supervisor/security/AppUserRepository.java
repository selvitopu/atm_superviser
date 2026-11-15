package io.github.selvitopu.atmsup.supervisor.security;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** The account with this exact, already normalised (trimmed, lower-case) username. */
    Optional<AppUser> findByUsername(String username);
}
