package com.moveinsync.zones.core.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.zones.core.error.StorageUnavailableException;
import com.moveinsync.zones.core.geo.Polygon;
import com.moveinsync.zones.core.zone.ZoneType;
import com.moveinsync.zones.core.zone.ZoneVersion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Durable repository: an append-only journal file with one JSON line per zone
 * version (a write-ahead log), plus an in-memory copy for reads.
 *
 * Write path: append the line, fsync, THEN update memory. If the process dies
 * mid-write, the file ends with a partial line and no newline. On startup we
 * replay every complete line and cut off that torn tail. A broken line in the
 * MIDDLE of the file is real corruption: we refuse to start rather than
 * silently serve wrong zones.
 */
public final class JournalZoneRepository implements ZoneRepository, AutoCloseable {

    /** One line of the journal. Plain types only, so the format is easy to read. */
    public record Entry(String tenantId, String zoneId, int version, String name, String type, int priority,
                        List<List<double[]>> coordinates, boolean deleted, long catalogVersion,
                        String author, String reason, String createdAt) {
    }

    public static final class JournalCorruptedException extends RuntimeException {
        public JournalCorruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Path file;
    private final InMemoryZoneRepository memory = new InMemoryZoneRepository();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final FileChannel channel;
    private final int recoveredEntries;
    private final long truncatedBytes;

    public JournalZoneRepository(Path file) {
        this.file = file;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
            long[] result = replay();
            this.recoveredEntries = (int) result[0];
            long goodLength = result[1];
            long size = Files.size(file);
            this.truncatedBytes = size - goodLength;
            this.channel = FileChannel.open(file, StandardOpenOption.WRITE);
            if (truncatedBytes > 0) {
                channel.truncate(goodLength); // drop the torn last write
                channel.force(true);
            }
            channel.position(goodLength);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open zone journal " + file, e);
        }
    }

    /** @return {entries replayed, byte length of the valid prefix} */
    private long[] replay() throws IOException {
        long offset = 0;
        long goodLength = 0;
        long entries = 0;
        int lineNumber = 0;
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream(4096);
            int b;
            while ((b = in.read()) != -1) {
                offset++;
                if (b != '\n') {
                    line.write(b);
                    continue;
                }
                lineNumber++;
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                if (!text.isBlank()) {
                    try {
                        memory.append(fromEntry(mapper.readValue(text, Entry.class)));
                    } catch (Exception e) {
                        throw new JournalCorruptedException("zone journal line " + lineNumber + " is corrupt", e);
                    }
                    entries++;
                }
                goodLength = offset;
            }
            // anything after the last '\n' is a torn write and is ignored
        }
        return new long[]{entries, goodLength};
    }

    @Override
    public synchronized void append(ZoneVersion v) {
        try {
            byte[] bytes = (mapper.writeValueAsString(toEntry(v)) + "\n").getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            long start = channel.position();
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(false);
            try {
                memory.append(v);
            } catch (RuntimeException e) {
                // keep file and memory in step: undo the line we just wrote
                channel.truncate(start);
                channel.position(start);
                throw e;
            }
        } catch (IOException e) {
            throw new StorageUnavailableException("zone journal write failed", e);
        }
    }

    @Override
    public long latestCatalogVersion(String tenantId) {
        return memory.latestCatalogVersion(tenantId);
    }

    @Override
    public Optional<ZoneVersion> latest(String tenantId, String zoneId) {
        return memory.latest(tenantId, zoneId);
    }

    @Override
    public List<ZoneVersion> history(String tenantId, String zoneId) {
        return memory.history(tenantId, zoneId);
    }

    @Override
    public List<ZoneVersion> activeAt(String tenantId, long catalogVersion) {
        return memory.activeAt(tenantId, catalogVersion);
    }

    @Override
    public Set<String> tenants() {
        return memory.tenants();
    }

    public int recoveredEntries() {
        return recoveredEntries;
    }

    public long truncatedBytes() {
        return truncatedBytes;
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }

    private static Entry toEntry(ZoneVersion v) {
        return new Entry(v.tenantId(), v.zoneId(), v.version(), v.name(), v.type().name(), v.priority(),
                v.polygon().toCoordinates(), v.deleted(), v.catalogVersion(), v.author(), v.reason(),
                v.createdAt().toString());
    }

    private static ZoneVersion fromEntry(Entry e) {
        return new ZoneVersion(e.tenantId(), e.zoneId(), e.version(), e.name(), ZoneType.valueOf(e.type()),
                e.priority(), Polygon.fromCoordinates(e.coordinates()), e.deleted(), e.catalogVersion(),
                e.author(), e.reason(), Instant.parse(e.createdAt()));
    }
}
