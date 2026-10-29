package com.moveinsync.zones.api.dto;

import com.moveinsync.zones.core.error.BadRequestException;
import com.moveinsync.zones.core.geo.Polygon;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** GeoJSON Polygon geometry: {"type":"Polygon","coordinates":[[[lng,lat],...], [hole...]]}. */
public record GeoJsonPolygon(@NotNull String type, @NotNull List<List<double[]>> coordinates) {

    public List<List<double[]>> requirePolygon() {
        if (!"Polygon".equals(type)) {
            throw new BadRequestException("INVALID_GEOMETRY", "geometry.type must be \"Polygon\"");
        }
        return coordinates;
    }

    public static GeoJsonPolygon of(Polygon polygon) {
        return new GeoJsonPolygon("Polygon", polygon.toCoordinates());
    }
}
