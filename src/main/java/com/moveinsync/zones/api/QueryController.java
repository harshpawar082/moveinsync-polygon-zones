package com.moveinsync.zones.api;

import com.moveinsync.zones.api.dto.QueryDtos;
import com.moveinsync.zones.api.dto.ZoneResponses;
import com.moveinsync.zones.core.attribution.TripSummary;
import com.moveinsync.zones.core.catalog.ZoneQueryService;
import com.moveinsync.zones.core.zone.ZoneVersion;
import com.moveinsync.zones.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** "Which zones is this point in?" and one-shot distance-by-zone for a full path. */
@RestController
@RequestMapping("/api/v1/query")
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class QueryController {

    private final ZoneQueryService queries;

    public QueryController(ZoneQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/point")
    public QueryDtos.PointResponse point(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam("lat") double lat,
                                         @RequestParam("lng") double lng,
                                         @RequestParam(value = "catalogVersion", required = false) Long catalogVersion) {
        ZoneQueryService.PointResult r = queries.query(CurrentUser.of(jwt).tenantId(), lat, lng, catalogVersion);
        return new QueryDtos.PointResponse(r.catalogVersion(), ZoneResponses.ZoneMatch.of(r.zones()));
    }

    @PostMapping("/points")
    public QueryDtos.BatchPointsResponse points(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody QueryDtos.BatchPointsRequest request) {
        ZoneQueryService.BatchResult r = queries.queryBatch(CurrentUser.of(jwt).tenantId(), request.toPoints(),
                request.catalogVersion());
        List<List<ZoneResponses.ZoneMatch>> results = new ArrayList<>(r.results().size());
        for (List<ZoneVersion> zones : r.results()) {
            results.add(ZoneResponses.ZoneMatch.of(zones));
        }
        return new QueryDtos.BatchPointsResponse(r.catalogVersion(), results);
    }

    @PostMapping("/attribution")
    public TripSummary attribution(@AuthenticationPrincipal Jwt jwt,
                                   @Valid @RequestBody QueryDtos.AttributionRequest request) {
        return queries.attribute(CurrentUser.of(jwt).tenantId(), "adhoc",
                QueryDtos.PingDto.toPings(request.pings()), request.catalogVersion());
    }
}
