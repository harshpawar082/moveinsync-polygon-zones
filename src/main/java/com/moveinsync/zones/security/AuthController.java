package com.moveinsync.zones.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record TokenRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, String tenant, String role) {
    }

    private final UserDirectory users;
    private final TokenService tokens;

    public AuthController(UserDirectory users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        UserDirectory.AppUser user = users.authenticate(request.username(), request.password())
                .orElseThrow(() -> new BadCredentialsException("bad credentials"));
        return new TokenResponse(tokens.issue(user), "Bearer", tokens.ttlSeconds(), user.tenant(), user.role().name());
    }
}
