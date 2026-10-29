package com.moveinsync.zones.api.dto;

import com.moveinsync.zones.core.attribution.Ping;
import com.moveinsync.zones.core.geo.GeoPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class QueryDtos {

    private QueryDtos() {
    }

    public record LatLng(@NotNull Double lat, @NotNull Double lng) {
    }

    public record PingDto(@NotNull Double lat, @NotNull Double lng, @NotNull Instant timestamp) {

        public Ping toPing() {
            return new Ping(lat, lng, timestamp.toEpochMilli());
        }

        public static List<Ping> toPings(List<PingDto> dtos) {
            List<Ping> out = new ArrayList<>(dtos.size());
            for (PingDto d : dtos) {
                out.add(d.toPing());
            }
            return out;
        }
    }

    public record PointResponse(long catalogVersion, List<ZoneResponses.ZoneMatch> zones) {
    }

    /** catalogVersion is optional: null = the live version. */
    public record BatchPointsRequest(@NotNull @Size(min = 1, max = 10_000) List<@Valid @NotNull LatLng> points,
                                     Long catalogVersion) {

        public List<GeoPoint> toPoints() {
            List<GeoPoint> out = new ArrayList<>(points.size());
            for (LatLng p : points) {
                out.add(new GeoPoint(p.lat(), p.lng()));
            }
            return out;
        }
    }

    public record BatchPointsResponse(long catalogVersion, List<List<ZoneResponses.ZoneMatch>> results) {
    }

    public record AttributionRequest(@NotNull @Size(min = 1, max = 10_000) List<@Valid @NotNull PingDto> pings,
                                     Long catalogVersion) {
    }
}
