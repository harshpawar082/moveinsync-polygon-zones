package com.moveinsync.zones.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Who is calling, taken ONLY from the signed token. The tenant is never read
 * from the URL or the request body, so a caller cannot ask for another
 * tenant's data even by editing the request.
 */
public record CurrentUser(String userId, String tenantId, Set<Role> roles) {

    public static CurrentUser of(Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");
        if (tenant == null || tenant.isBlank()) {
            throw new AccessDeniedException("token has no tenant");
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        List<String> claimed = jwt.getClaimAsStringList("roles");
        if (claimed != null) {
            for (String r : claimed) {
                roles.add(Role.valueOf(r));
            }
        }
        return new CurrentUser(jwt.getSubject(), tenant, roles);
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    /** Only employees are restricted to their own trips. */
    public boolean isOnlyEmployee() {
        return roles.contains(Role.EMPLOYEE) && !roles.contains(Role.OPERATOR) && !roles.contains(Role.ADMIN);
    }
}
