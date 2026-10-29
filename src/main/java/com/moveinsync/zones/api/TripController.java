package com.moveinsync.zones.api;

import com.moveinsync.zones.api.dto.QueryDtos;
import com.moveinsync.zones.api.dto.TripDtos;
import com.moveinsync.zones.core.attribution.TripSummary;
import com.moveinsync.zones.core.error.NotFoundException;
import com.moveinsync.zones.core.trip.TripService;
import com.moveinsync.zones.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Set;

/** Live trips: start, stream pings, end, read the billing split. */
@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripService trips;

    public TripController(TripService trips) {
        this.trips = trips;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<TripDtos.TripStarted> start(@AuthenticationPrincipal Jwt jwt,
                                                      @Valid @RequestBody TripDtos.StartTrip request) {
        Set<String> riders = new HashSet<>();
        if (request.riderIds() != null) {
            riders.addAll(request.riderIds());
        }
        TripService.TripInfo info = trips.start(CurrentUser.of(jwt).tenantId(), request.tripId(), request.cabId(), riders);
        return ResponseEntity.status(HttpStatus.CREATED).body(new TripDtos.TripStarted(info.tripId(), info.cabId(),
                info.riderIds(), info.billingCatalogVersion(), info.startedAt()));
    }

    @PostMapping("/{tripId}/pings")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public TripService.IngestResult pings(@AuthenticationPrincipal Jwt jwt, @PathVariable("tripId") String tripId,
                                          @Valid @RequestBody TripDtos.PingBatch request) {
        return trips.ingest(CurrentUser.of(jwt).tenantId(), tripId, QueryDtos.PingDto.toPings(request.pings()));
    }

    @PostMapping("/{tripId}/end")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public TripSummary end(@AuthenticationPrincipal Jwt jwt, @PathVariable("tripId") String tripId) {
        return trips.end(CurrentUser.of(jwt).tenantId(), tripId);
    }

    /** Employees may read only trips they ride in; for anyone else's trip they get 404, not 403. */
    @GetMapping("/{tripId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','EMPLOYEE')")
    public TripSummary get(@AuthenticationPrincipal Jwt jwt, @PathVariable("tripId") String tripId) {
        CurrentUser user = CurrentUser.of(jwt);
        TripService.TripInfo info = trips.info(user.tenantId(), tripId);
        if (user.isOnlyEmployee() && !info.riderIds().contains(user.userId())) {
            throw new NotFoundException("TRIP_NOT_FOUND", "trip " + tripId + " not found");
        }
        return trips.summary(user.tenantId(), tripId);
    }
}
