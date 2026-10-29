package com.moveinsync.zones.api.dto;

import com.moveinsync.zones.api.dto.QueryDtos.PingDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class TripDtos {

    private TripDtos() {
    }

    public record StartTrip(@NotBlank String tripId, @NotBlank String cabId, @Size(max = 20) List<String> riderIds) {
    }

    public record TripStarted(String tripId, String cabId, Set<String> riderIds, long billingCatalogVersion,
                              Instant startedAt) {
    }

    public record PingBatch(@NotNull @Size(min = 1, max = 5_000) List<@Valid @NotNull PingDto> pings) {
    }
}
