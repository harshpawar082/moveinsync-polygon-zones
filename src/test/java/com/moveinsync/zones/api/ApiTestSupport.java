package com.moveinsync.zones.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.zones.core.catalog.SnapshotManager;
import com.moveinsync.zones.core.catalog.ZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared helpers: real login through the real security chain, in-memory storage. */
abstract class ApiTestSupport {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected SnapshotManager snapshots;

    @Autowired
    protected ZoneRepository repository;

    @BeforeEach
    void waitForSeededIndex() {
        snapshots.awaitVersion("acme", repository.latestCatalogVersion("acme"), Duration.ofSeconds(10));
        snapshots.awaitVersion("globex", repository.latestCatalogVersion("globex"), Duration.ofSeconds(10));
    }

    protected String token(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    protected String bearer(String username, String password) throws Exception {
        return "Bearer " + token(username, password);
    }
}
