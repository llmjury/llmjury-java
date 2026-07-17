package com.llmjury.sdk;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The default {@link Transport}: {@code java.net.http.HttpClient} (Java 11+) against the ingest
 * plane, authenticated with the org <b>publishable</b> key via {@code X-API-Key}. JSON via Gson —
 * the SDK's only runtime dependency.
 */
public final class HttpTransport implements Transport {

    private static final int DEFAULT_BUCKET_COUNT = 1000;
    private static final Gson GSON = new Gson();

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    public HttpTransport(String baseUrl, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public ConfigResponse getConfig(String experimentId, String etag) throws Exception {
        String url = baseUrl + "/v1/config";
        if (experimentId != null && !experimentId.isEmpty()) {
            url += "?experiment_id=" + URLEncoder.encode(experimentId, StandardCharsets.UTF_8);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("X-API-Key", apiKey)
                .GET();
        if (etag != null) {
            request.header("If-None-Match", etag);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 304) {
            return ConfigResponse.notModified(etag);
        }
        if (response.statusCode() != 200) {
            throw new IOException("config fetch failed: HTTP " + response.statusCode());
        }
        String responseEtag = response.headers().firstValue("ETag").orElse(null);
        return new ConfigResponse(200, parseConfigs(response.body()), responseEtag);
    }

    @Override
    public IngestAck postEvents(List<Map<String, Object>> events) throws Exception {
        String body = GSON.toJson(Collections.singletonMap("events", events));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/events"))
                .timeout(timeout)
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("event ingest failed: HTTP " + response.statusCode());
        }
        return parseAck(response.body());
    }

    private static List<CachedConfig> parseConfigs(String body) {
        if (body == null || body.isEmpty()) {
            return Collections.emptyList();
        }
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray()) {
            return Collections.emptyList();
        }
        List<CachedConfig> configs = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            CachedConfig config = parseConfig(element.getAsJsonObject());
            if (config != null) {
                configs.add(config);
            }
        }
        return configs;
    }

    /** Mirrors the reference Python SDK's parse: tolerate {@code id} or {@code experiment_id}, skip malformed entries. */
    private static CachedConfig parseConfig(JsonObject raw) {
        String experimentId = stringField(raw, "id");
        if (experimentId == null) {
            experimentId = stringField(raw, "experiment_id");
        }
        if (experimentId == null || !raw.has("salt") || !raw.has("allocation")) {
            return null;
        }
        JsonArray rawAllocation = raw.getAsJsonArray("allocation");
        List<AllocationSlice> allocation = new ArrayList<>(rawAllocation.size());
        for (JsonElement element : rawAllocation) {
            JsonObject slice = element.getAsJsonObject();
            java.util.Map<String, String> variables = new java.util.HashMap<>();
            if (slice.has("variables") && slice.get("variables").isJsonObject()) {
                for (var entry : slice.getAsJsonObject("variables").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        variables.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            allocation.add(new AllocationSlice(
                    slice.get("variant").getAsString(),
                    slice.get("weight").getAsInt(),
                    stringField(slice, "prompt"),
                    variables));
        }
        int bucketCount = raw.has("bucket_count") ? raw.get("bucket_count").getAsInt() : DEFAULT_BUCKET_COUNT;
        long version = raw.has("version") ? raw.get("version").getAsLong() : 0L;
        return new CachedConfig(
                experimentId,
                raw.get("salt").getAsString(),
                bucketCount,
                allocation,
                version,
                stringField(raw, "name"));
    }

    private static String stringField(JsonObject raw, String name) {
        if (!raw.has(name) || raw.get(name).isJsonNull()) {
            return null;
        }
        String value = raw.get(name).getAsString();
        return value.isEmpty() ? null : value;
    }

    private static IngestAck parseAck(String body) {
        if (body == null || body.isEmpty()) {
            return new IngestAck(0, 0);
        }
        JsonObject ack = JsonParser.parseString(body).getAsJsonObject();
        long accepted = ack.has("accepted") ? ack.get("accepted").getAsLong() : 0L;
        long configVersion =
                ack.has("config_version") ? ack.get("config_version").getAsLong() : 0L;
        return new IngestAck(accepted, configVersion);
    }
}
