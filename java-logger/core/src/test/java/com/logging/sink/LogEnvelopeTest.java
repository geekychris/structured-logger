package com.logging.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogEnvelopeTest {

    @Test
    void serialisesWithRoutingMetadataAtTopLevel() throws Exception {
        Map<String, Object> data = Map.of("user_id", "u1", "event_type", "click");
        LogEnvelope envelope = new LogEnvelope("u1", "user_events", "UserEvents", "1.0.0", data);

        String json = EnvelopeSerializer.toJson(envelope);
        JsonNode node = new ObjectMapper().readTree(json);

        assertThat(node.get("_log_type").asText()).isEqualTo("user_events");
        assertThat(node.get("_log_class").asText()).isEqualTo("UserEvents");
        assertThat(node.get("_version").asText()).isEqualTo("1.0.0");
        assertThat(node.get("data").get("user_id").asText()).isEqualTo("u1");
    }

    @Test
    void rejectsNullRequiredFields() {
        assertThatThrownBy(() -> new LogEnvelope("k", null, "C", "1", "data"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void mapPreservesInsertionOrder() {
        LogEnvelope envelope = new LogEnvelope("k", "t", "c", "v", "d");
        Map<String, Object> map = envelope.toMap();
        assertThat(map.keySet()).containsExactly("_log_type", "_log_class", "_version", "data");
    }
}
