package com.moveinsync.zones.api;

import com.moveinsync.zones.core.catalog.SnapshotManager;
import com.moveinsync.zones.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** Which zone catalog version is live, and is anything waiting to go live. */
@RestController
@RequestMapping("/api/v1/index")
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class IndexController {

    private final SnapshotManager snapshots;

    public IndexController(SnapshotManager snapshots) {
        this.snapshots = snapshots;
    }

    /** waitForVersion: block up to 5 s until that catalog version is live (handy after an edit). */
    @GetMapping("/status")
    public SnapshotManager.IndexStatus status(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(value = "waitForVersion", required = false) Long waitForVersion) {
        String tenant = CurrentUser.of(jwt).tenantId();
        if (waitForVersion != null) {
            snapshots.awaitVersion(tenant, waitForVersion, Duration.ofSeconds(5));
        }
        return snapshots.status(tenant);
    }
}
