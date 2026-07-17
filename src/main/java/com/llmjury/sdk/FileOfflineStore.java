package com.llmjury.sdk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * File-backed {@link OfflineStore}: newline-delimited JSON, each line
 * {@code {"event": <event>, "saved_at": <epoch millis>}}. {@code saved_at} is wall-clock at spill
 * time and is what the 24-hour bound is measured against; the event's own {@code timestamp} field
 * is left untouched so the replay is faithful. The format mirrors the Python/TS SDKs' spill files.
 */
public final class FileOfflineStore implements OfflineStore {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    // LONG_OR_DOUBLE keeps integer event fields integers across a spill/replay round-trip.
    private static final Gson GSON = new GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create();
    private static final TypeToken<Map<String, Object>> EVENT_TYPE = new TypeToken<Map<String, Object>>() {};

    private final Path path;
    private final long maxAgeMillis;
    private final LongSupplier nowMillis;
    private final System.Logger log;
    private final Object lock = new Object();

    public FileOfflineStore(String path) {
        this(path, DAY_MILLIS, System::currentTimeMillis);
    }

    FileOfflineStore(String path, long maxAgeMillis, LongSupplier nowMillis) {
        this.path = Paths.get(path);
        this.maxAgeMillis = maxAgeMillis;
        this.nowMillis = nowMillis;
        this.log = System.getLogger("llmjury");
    }

    @Override
    public void appendAll(List<Map<String, Object>> events) {
        if (events.isEmpty()) {
            return;
        }
        long savedAt = nowMillis.getAsLong();
        StringBuilder lines = new StringBuilder();
        for (Map<String, Object> event : events) {
            JsonObject record = new JsonObject();
            record.add("event", GSON.toJsonTree(event));
            record.addProperty("saved_at", savedAt);
            lines.append(GSON.toJson(record)).append('\n');
        }
        synchronized (lock) {
            try {
                Path directory = path.getParent();
                if (directory != null) {
                    Files.createDirectories(directory);
                }
                Files.write(
                        path,
                        lines.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException exception) { // disk full / permission — last resort, just log.
                log.log(
                        System.Logger.Level.WARNING,
                        "llmjury: failed to persist " + events.size() + " offline events: " + exception);
            }
        }
    }

    @Override
    public List<Map<String, Object>> loadReplayable() {
        long cutoff = nowMillis.getAsLong() - maxAgeMillis;
        List<String> lines;
        synchronized (lock) {
            try {
                if (!Files.exists(path)) {
                    return Collections.emptyList();
                }
                lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                log.log(System.Logger.Level.WARNING, "llmjury: failed to read offline buffer: " + exception);
                return Collections.emptyList();
            }
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            JsonObject record;
            try {
                JsonElement parsed = JsonParser.parseString(trimmed);
                if (!parsed.isJsonObject()) {
                    continue;
                }
                record = parsed.getAsJsonObject();
            } catch (RuntimeException malformed) {
                continue; // tolerate a torn final line from a crash mid-write.
            }
            long savedAt = record.has("saved_at") ? record.get("saved_at").getAsLong() : 0L;
            if (savedAt >= cutoff && record.has("event")) {
                events.add(GSON.fromJson(record.get("event"), EVENT_TYPE));
            }
        }
        return events;
    }

    @Override
    public void clear() {
        synchronized (lock) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                log.log(System.Logger.Level.WARNING, "llmjury: failed to clear offline buffer: " + exception);
            }
        }
    }
}
