package dev.hmcodes.jrap.tenancy.service;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** Argon2id password hashing (FR-AUTH-2, NFR-SEC-1). */
@Component
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    /** Hash of an unguessable value, used to equalise timing when the user does not exist. */
    private final String dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String storedHash) {
        if (storedHash == null) {
            encoder.matches(rawPassword, dummyHash);
            return false;
        }
        return encoder.matches(rawPassword, storedHash);
    }
}
