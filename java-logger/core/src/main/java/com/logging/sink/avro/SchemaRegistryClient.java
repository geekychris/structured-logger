package com.logging.sink.avro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal Confluent Schema Registry client. Just register + fetch schemas — we
 * don't need the heavyweight io.confluent client or its dependency tree.
 */
public final class SchemaRegistryClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CT = "application/vnd.schemaregistry.v1+json";

    private final String baseUrl;
    private final HttpClient http;

    public SchemaRegistryClient(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Schema Registry URL is required");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Register the schema under {@code subject} and return the assigned id. */
    public int register(String subject, String schemaJson) {
        try {
            String body = MAPPER.writeValueAsString(java.util.Map.of(
                    "schemaType", "AVRO",
                    "schema", schemaJson));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/subjects/" + subject + "/versions"))
                    .header("Content-Type", CT)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("Schema Registry register " + subject
                        + " failed: HTTP " + resp.statusCode() + " body=" + resp.body());
            }
            JsonNode node = MAPPER.readTree(resp.body());
            return node.get("id").asInt();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Schema Registry register failed", e);
        }
    }

    /** Fetch a schema by id (raw schema JSON). */
    public String fetchById(int id) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/schemas/ids/" + id))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("Schema Registry fetch " + id
                        + " failed: HTTP " + resp.statusCode() + " body=" + resp.body());
            }
            return MAPPER.readTree(resp.body()).get("schema").asText();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Schema Registry fetch failed", e);
        }
    }
}
