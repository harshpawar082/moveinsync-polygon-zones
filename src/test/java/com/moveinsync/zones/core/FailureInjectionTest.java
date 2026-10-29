package com.moveinsync.zones.core;

import com.moveinsync.zones.core.catalog.InMemoryZoneRepository;
import com.moveinsync.zones.core.catalog.JournalZoneRepository;
import com.moveinsync.zones.core.index.GridSnapshotBuilder;
import com.moveinsync.zones.core.index.SnapshotBuilder;
import com.moveinsync.zones.core.index.ZoneSnapshot;
import com.moveinsync.zones.core.zone.ZoneType;
import com.moveinsync.zones.core.zone.ZoneVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.moveinsync.zones.core.TestEnv.draft;
import static com.moveinsync.zones.core.TestShapes.coords;
import static com.moveinsync.zones.core.TestShapes.rect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureInjectionTest {

    /** A builder that throws for the next N builds (e.g. out of memory, bug in a new polygon). */
    static final class FlakyBuilder implements SnapshotBuilder {
        final GridSnapshotBuilder real = new GridSnapshotBuilder(0.01, 4096);
        final AtomicInteger failNext = new AtomicInteger();

        @Override
        public ZoneSnapshot build(String tenantId, long catalogVersion, List<ZoneVersion> zones) {
            if (failNext.getAndDecrement() > 0) {
                throw new IllegalStateException("injected build failure");
            }
            return real.build(tenantId, catalogVersion, zones);
        }
    }

    @Test
    void failedRebuildKeepsServingTheOldIndexThenRecovers() throws Exception {
        FlakyBuilder builder = new FlakyBuilder();
        try (TestEnv env = new TestEnv(new InMemoryZoneRepository(), builder)) {
            env.catalog.create("t1", "a", draft("Z", ZoneType.TARIFF, 1, coords(rect(77.0, 12.0, 77.1, 12.1))));
            env.awaitIndex("t1");

            builder.failNext.set(2);
            env.catalog.update("t1", "a", "Z", 1, draft("Z", ZoneType.TARIFF, 1, coords(rect(77.5, 12.0, 77.6, 12.1))));

            // While builds fail: version 1 is still live and still answers correctly.
            Thread.sleep(10);
            assertEquals(1, env.snapshots.current("t1").catalogVersion());
            assertEquals(1, env.queries.query("t1", 12.05, 77.05, null).zones().size());

            // Retries with backoff eventually publish version 2.
            assertTrue(env.snapshots.awaitVersion("t1", 2, Duration.ofSeconds(5)));
            assertEquals(2, env.metrics.rebuildFailures.get());
            assertEquals(0, env.snapshots.status("t1").consecutiveFailures());
            assertEquals(0, env.queries.query("t1", 12.05, 77.05, null).zones().size());
            assertEquals(1, env.queries.query("t1", 12.05, 77.55, null).zones().size());
        }
    }

    @Test
    void tornLastWriteIsCutOffOnRestart() throws IOException {
        Path file = Files.createTempDirectory("journal").resolve("zones.jsonl");
        try (JournalZoneRepository repo = new JournalZoneRepository(file); TestEnv env = new TestEnv(repo, new GridSnapshotBuilder(0.01, 4096))) {
            env.catalog.create("t1", "a", draft("A", ZoneType.TARIFF, 1, coords(rect(77.0, 12.0, 77.1, 12.1), rect(77.02, 12.02, 77.03, 12.03))));
            env.catalog.create("t1", "a", draft("B", ZoneType.AIRPORT, 5, coords(rect(77.2, 12.0, 77.3, 12.1))));
            env.catalog.update("t1", "a", "A", 1, draft("A", ZoneType.TARIFF, 2, coords(rect(77.0, 12.0, 77.15, 12.1))));
        }
        // simulate a crash in the middle of writing the 4th line
        Files.write(file, "{\"tenantId\":\"t1\",\"zoneId\":\"C\",\"vers".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        try (JournalZoneRepository repo = new JournalZoneRepository(file)) {
            assertEquals(3, repo.recoveredEntries());
            assertTrue(repo.truncatedBytes() > 0);
            assertEquals(3, repo.latestCatalogVersion("t1"));
            // the store keeps working after recovery
            try (TestEnv env = new TestEnv(repo, new GridSnapshotBuilder(0.01, 4096))) {
                assertEquals(3, env.snapshots.current("t1").catalogVersion()); // index rebuilt at startup
                assertEquals(1, env.queries.query("t1", 12.05, 77.12, null).zones().size());
                env.catalog.create("t1", "a", draft("C", ZoneType.NO_GO, 0, coords(rect(77.4, 12.0, 77.5, 12.1))));
            }
        }
        try (JournalZoneRepository repo = new JournalZoneRepository(file)) {
            assertEquals(4, repo.recoveredEntries());
            assertEquals(0, repo.truncatedBytes());
            // holes survive the round trip
            ZoneVersion a1 = repo.history("t1", "A").get(0);
            assertEquals(1, a1.polygon().holes().size());
            assertTrue(!a1.polygon().contains(77.025, 12.025));
        }
    }

    @Test
    void corruptionInTheMiddleRefusesToStart() throws IOException {
        Path file = Files.createTempDirectory("journal").resolve("zones.jsonl");
        try (JournalZoneRepository repo = new JournalZoneRepository(file); TestEnv env = new TestEnv(repo, new GridSnapshotBuilder(0.01, 4096))) {
            env.catalog.create("t1", "a", draft("A", ZoneType.TARIFF, 1, coords(rect(77.0, 12.0, 77.1, 12.1))));
        }
        String good = Files.readString(file);
        Files.writeString(file, "this is not json\n" + good);
        assertThrows(JournalZoneRepository.JournalCorruptedException.class, () -> new JournalZoneRepository(file));
    }
}
