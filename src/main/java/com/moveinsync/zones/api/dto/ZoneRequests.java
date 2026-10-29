package com.moveinsync.zones.api.dto;

import com.moveinsync.zones.core.catalog.ZoneCatalogService;
import com.moveinsync.zones.core.zone.ZoneType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request bodies for zone writes. Business rules are checked again in the core. */
public final class ZoneRequests {

    private ZoneRequests() {
    }

    public record CreateZone(@NotBlank String zoneId, @NotBlank String name, @NotNull ZoneType type,
                             @NotNull Integer priority, @NotNull @Valid GeoJsonPolygon geometry, String reason) {

        public ZoneCatalogService.ZoneDraft toDraft() {
            return new ZoneCatalogService.ZoneDraft(zoneId, name, type, priority, geometry.requirePolygon(), reason);
        }
    }

    /** expectedVersion = the version the admin was looking at (optimistic concurrency). */
    public record UpdateZone(@NotNull Integer expectedVersion, @NotBlank String name, @NotNull ZoneType type,
                             @NotNull Integer priority, @NotNull @Valid GeoJsonPolygon geometry, String reason) {

        public ZoneCatalogService.ZoneDraft toDraft(String zoneId) {
            return new ZoneCatalogService.ZoneDraft(zoneId, name, type, priority, geometry.requirePolygon(), reason);
        }
    }
}
