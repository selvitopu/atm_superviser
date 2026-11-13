package io.github.selvitopu.atmsup.inventory;

/** SNMPv3 authPriv passwords of one user. */
public record UserCredentials(String authPassword, String privPassword) {

    public UserCredentials {
        if (authPassword == null || authPassword.length() < 8 || privPassword == null || privPassword.length() < 8) {
            throw new IllegalArgumentException("SNMPv3 passwords must be at least 8 characters");
        }
    }
}
