package com.moveinsync.zones.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.zones.seed.DemoZones;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.storage.journal-path=")
@AutoConfigureMockMvc
class ZoneApiFlowTest extends ApiTestSupport {

    private static String zoneJson(String zoneId, double minLng, double minLat, double maxLng, double maxLat) {
        return "{\"zoneId\":\"" + zoneId + "\",\"name\":\"Test zone\",\"type\":\"TARIFF\",\"priority\":3,"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[" + minLng + "," + minLat + "],[" + maxLng + ","
                + minLat + "],[" + maxLng + "," + maxLat + "],[" + minLng + "," + maxLat + "],[" + minLng + "," + minLat
                + "]]]},\"reason\":\"test\"}";
    }

    private static String updateJson(int expectedVersion, double minLng, double minLat, double maxLng, double maxLat) {
        return "{\"expectedVersion\":" + expectedVersion + ",\"name\":\"Test zone\",\"type\":\"TARIFF\",\"priority\":3,"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[" + minLng + "," + minLat + "],[" + maxLng + ","
                + minLat + "],[" + maxLng + "," + maxLat + "],[" + minLng + "," + maxLat + "],[" + minLng + "," + minLat
                + "]]]},\"reason\":\"moved\"}";
    }

    @Test
    void selfIntersectingPolygonIsRejectedWithReasons() throws Exception {
        String bowTie = "{\"zoneId\":\"BOWTIE\",\"name\":\"bad\",\"type\":\"TARIFF\",\"priority\":1,"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[77.0,12.0],[77.1,12.1],[77.1,12.0],[77.0,12.1],[77.0,12.0]]]}}";
        mvc.perform(post("/api/v1/zones").header("Authorization", bearer("admin-acme", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON).content(bowTie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POLYGON"))
                .andExpect(jsonPath("$.details[0]", containsString("SELF_INTERSECTION")));
    }

    @Test
    void createEditConflictAndQueryTheNewShape() throws Exception {
        String admin = bearer("admin-acme", "admin123");
        MvcResult created = mvc.perform(post("/api/v1/zones").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(zoneJson("FLOW_ZONE", 76.0, 11.0, 76.1, 11.1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        long v1Catalog = mapper.readTree(created.getResponse().getContentAsString()).get("catalogVersion").asLong();

        String update = updateJson(1, 76.5, 11.0, 76.6, 11.1);
        MvcResult updated = mvc.perform(put("/api/v1/zones/FLOW_ZONE").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andReturn();
        long v2Catalog = mapper.readTree(updated.getResponse().getContentAsString()).get("catalogVersion").asLong();

        // a second admin still editing version 1 gets a conflict, not a silent overwrite
        mvc.perform(put("/api/v1/zones/FLOW_ZONE").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mvc.perform(get("/api/v1/index/status").param("waitForVersion", String.valueOf(v2Catalog))
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCatalogVersion").value((int) v2Catalog));

        mvc.perform(get("/api/v1/query/point").param("lat", "11.05").param("lng", "76.55").header("Authorization", admin))
                .andExpect(jsonPath("$.zones[0].zoneId").value("FLOW_ZONE"));
        mvc.perform(get("/api/v1/query/point").param("lat", "11.05").param("lng", "76.05").header("Authorization", admin))
                .andExpect(jsonPath("$.zones", hasSize(0)));
        // the old shape is still answerable at the old catalog version
        mvc.perform(get("/api/v1/query/point").param("lat", "11.05").param("lng", "76.05")
                        .param("catalogVersion", String.valueOf(v1Catalog)).header("Authorization", admin))
                .andExpect(jsonPath("$.zones[0].zoneId").value("FLOW_ZONE"));

        mvc.perform(get("/api/v1/zones/FLOW_ZONE/versions").header("Authorization", admin))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void airportTripThroughTheApiIsSplitAtTheTollPlaza() throws Exception {
        StringBuilder pings = new StringBuilder();
        Instant start = Instant.parse("2026-10-30T08:30:00Z");
        for (int i = 0; i <= 84; i++) {
            if (i > 0) {
                pings.append(',');
            }
            double lat = DemoZones.TRIP_START_LAT + i * 0.5 * DemoZones.DEG_PER_KM;
            pings.append("{\"lat\":").append(lat).append(",\"lng\":").append(DemoZones.TRIP_LNG)
                    .append(",\"timestamp\":\"").append(start.plusSeconds(60L * i)).append("\"}");
        }
        MvcResult r = mvc.perform(post("/api/v1/query/attribution").header("Authorization", bearer("operator-acme", "oper123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pings\":[" + pings + "]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(r.getResponse().getContentAsString());
        if (body.get("totalKm").decimalValue().compareTo(new java.math.BigDecimal("42.000")) != 0) {
            throw new AssertionError("total " + body.get("totalKm"));
        }
        JsonNode parts = body.get("distanceByZone");
        if (!parts.get(0).get("zoneId").asText().equals("BLR_AIRPORT")
                || parts.get(0).get("km").decimalValue().compareTo(new java.math.BigDecimal("11.600")) != 0
                || parts.get(1).get("km").decimalValue().compareTo(new java.math.BigDecimal("30.400")) != 0) {
            throw new AssertionError("unexpected split " + parts);
        }
    }
}
