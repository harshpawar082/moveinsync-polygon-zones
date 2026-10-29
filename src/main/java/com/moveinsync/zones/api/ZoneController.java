package com.moveinsync.zones.api;

import com.moveinsync.zones.api.dto.ZoneRequests;
import com.moveinsync.zones.api.dto.ZoneResponses;
import com.moveinsync.zones.core.catalog.ZoneCatalogService;
import com.moveinsync.zones.core.zone.ZoneVersion;
import com.moveinsync.zones.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Zone catalog. Writes: ADMIN only. Reads: ADMIN and OPERATOR. Always within the caller's tenant. */
@RestController
@RequestMapping("/api/v1/zones")
public class ZoneController {

    private final ZoneCatalogService catalog;

    public ZoneController(ZoneCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ZoneResponses.Zone> create(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody ZoneRequests.CreateZone request) {
        CurrentUser user = CurrentUser.of(jwt);
        ZoneVersion created = catalog.create(user.tenantId(), user.userId(), request.toDraft());
        return ResponseEntity.status(HttpStatus.CREATED).body(ZoneResponses.Zone.of(created));
    }

    @PutMapping("/{zoneId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ZoneResponses.Zone update(@AuthenticationPrincipal Jwt jwt, @PathVariable("zoneId") String zoneId,
                                     @Valid @RequestBody ZoneRequests.UpdateZone request) {
        CurrentUser user = CurrentUser.of(jwt);
        return ZoneResponses.Zone.of(catalog.update(user.tenantId(), user.userId(), zoneId,
                request.expectedVersion(), request.toDraft(zoneId)));
    }

    @DeleteMapping("/{zoneId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ZoneResponses.ZoneSummary delete(@AuthenticationPrincipal Jwt jwt, @PathVariable("zoneId") String zoneId,
                                            @RequestParam("expectedVersion") int expectedVersion,
                                            @RequestParam(value = "reason", required = false) String reason) {
        CurrentUser user = CurrentUser.of(jwt);
        return ZoneResponses.ZoneSummary.of(catalog.delete(user.tenantId(), user.userId(), zoneId, expectedVersion, reason));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public List<ZoneResponses.ZoneSummary> list(@AuthenticationPrincipal Jwt jwt) {
        return ZoneResponses.ZoneSummary.of(catalog.listActive(CurrentUser.of(jwt).tenantId()));
    }

    @GetMapping("/{zoneId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ZoneResponses.Zone get(@AuthenticationPrincipal Jwt jwt, @PathVariable("zoneId") String zoneId) {
        return ZoneResponses.Zone.of(catalog.latest(CurrentUser.of(jwt).tenantId(), zoneId));
    }

    @GetMapping("/{zoneId}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public List<ZoneResponses.ZoneSummary> history(@AuthenticationPrincipal Jwt jwt, @PathVariable("zoneId") String zoneId) {
        return ZoneResponses.ZoneSummary.of(catalog.history(CurrentUser.of(jwt).tenantId(), zoneId));
    }

    @GetMapping("/{zoneId}/versions/{version}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ZoneResponses.Zone version(@AuthenticationPrincipal Jwt jwt, @PathVariable("zoneId") String zoneId,
                                      @PathVariable("version") int version) {
        return ZoneResponses.Zone.of(catalog.version(CurrentUser.of(jwt).tenantId(), zoneId, version));
    }
}
