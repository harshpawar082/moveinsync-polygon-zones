package com.moveinsync.zones.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.storage.journal-path=")
@AutoConfigureMockMvc
class SecurityAndTenantIsolationTest extends ApiTestSupport {

    @Test
    void noTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/zones"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void forgedTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/zones").header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.bad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordGetsAGenericMessage() throws Exception {
        mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin-acme\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid username or password"));
    }

    @Test
    void employeeCannotEditZones() throws Exception {
        mvc.perform(post("/api/v1/zones").header("Authorization", bearer("employee-acme", "emp123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void operatorCannotEditZones() throws Exception {
        mvc.perform(post("/api/v1/zones").header("Authorization", bearer("operator-acme", "oper123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherTenantsZoneLooksLikeItDoesNotExist() throws Exception {
        mvc.perform(get("/api/v1/zones/BLR_AIRPORT").header("Authorization", bearer("admin-acme", "admin123")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/zones/BLR_AIRPORT").header("Authorization", bearer("admin-globex", "admin123")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
        mvc.perform(get("/api/v1/zones/BLR_AIRPORT/versions").header("Authorization", bearer("admin-globex", "admin123")))
                .andExpect(status().isNotFound());
    }

    @Test
    void pointQueriesOnlySeeTheCallersTenant() throws Exception {
        mvc.perform(get("/api/v1/query/point").param("lat", "13.2").param("lng", "77.65")
                        .header("Authorization", bearer("operator-acme", "oper123")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BLR_AIRPORT")))
                .andExpect(content().string(not(containsString("GLOBEX_NORTH"))));
        mvc.perform(get("/api/v1/query/point").param("lat", "13.2").param("lng", "77.65")
                        .header("Authorization", bearer("operator-globex", "oper123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zones", hasSize(1)))
                .andExpect(jsonPath("$.zones[0].zoneId").value("GLOBEX_NORTH"));
    }

    @Test
    void employeesSeeOnlyTripsTheyRideIn() throws Exception {
        String operator = bearer("operator-acme", "oper123");
        mvc.perform(post("/api/v1/trips").header("Authorization", operator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":\"T-ISO-1\",\"cabId\":\"KA01AB1234\",\"riderIds\":[\"employee-acme\"]}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/trips/T-ISO-1").header("Authorization", bearer("employee-acme", "emp123")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/trips/T-ISO-1").header("Authorization", bearer("employee-acme-2", "emp123")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trips/T-ISO-1").header("Authorization", bearer("operator-globex", "oper123")))
                .andExpect(status().isNotFound());
    }

    @Test
    void errorsNeverLeakInternals() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/zones").header("Authorization", bearer("admin-acme", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"zoneId\": [broken json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.requestId").exists())
                .andReturn();
        String body = r.getResponse().getContentAsString();
        if (body.contains("Exception") || body.contains("at com.") || body.contains("jackson")) {
            throw new AssertionError("error body leaks internals: " + body);
        }
    }

    @Test
    void healthAndPrometheusArePublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("zones_index_pending_versions")));
    }
}
