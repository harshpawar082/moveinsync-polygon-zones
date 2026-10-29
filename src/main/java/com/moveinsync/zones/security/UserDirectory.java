package com.moveinsync.zones.security;

import com.moveinsync.zones.config.AppProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Demo user store. Passwords are kept only as BCrypt hashes. In production this
 * would be replaced by the company's identity provider issuing the same JWTs.
 */
@Component
public class UserDirectory {

    public record AppUser(String username, String passwordHash, String tenant, Role role) {
    }

    private final Map<String, AppUser> users = new HashMap<>();
    private final PasswordEncoder encoder;
    private final String dummyHash;

    public UserDirectory(AppProperties props, PasswordEncoder encoder) {
        this.encoder = encoder;
        for (AppProperties.Security.User u : props.security().users()) {
            users.put(u.username(), new AppUser(u.username(), encoder.encode(u.password()), u.tenant(),
                    Role.valueOf(u.role())));
        }
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    public Optional<AppUser> authenticate(String username, String password) {
        AppUser user = users.get(username);
        if (user == null) {
            encoder.matches(password, dummyHash); // same work as a real check: no timing hint that the user exists
            return Optional.empty();
        }
        if (!encoder.matches(password, user.passwordHash())) {
            return Optional.empty();
        }
        return Optional.of(user);
    }
}
