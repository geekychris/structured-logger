package com.logging.sink.avro;

import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AvroEnvelopeSchemaTest {

    @Test
    void buildsEnvelopeWithDataRecordOfRequiredAndOptionalFields() {
        Schema schema = AvroEnvelopeSchema.derive("UserEvents", List.of(
                Map.of("name", "user_id", "type", "string", "required", true),
                Map.of("name", "session_id", "type", "string", "required", false),
                Map.of("name", "duration_ms", "type", "long", "required", false),
                Map.of("name", "props", "type", "map<string,string>", "required", true)
        ));

        assertThat(schema.getName()).isEqualTo("UserEventsEnvelope");
        Schema dataSchema = schema.getField("data").schema();
        assertThat(dataSchema.getName()).isEqualTo("UserEventsData");

        // required field: simple type
        assertThat(dataSchema.getField("user_id").schema().getType()).isEqualTo(Schema.Type.STRING);

        // optional field: union of null + base
        Schema sessionSchema = dataSchema.getField("session_id").schema();
        assertThat(sessionSchema.getType()).isEqualTo(Schema.Type.UNION);
        assertThat(sessionSchema.getTypes()).extracting(Schema::getType)
                .containsExactly(Schema.Type.NULL, Schema.Type.STRING);

        // map type
        Schema propsSchema = dataSchema.getField("props").schema();
        assertThat(propsSchema.getType()).isEqualTo(Schema.Type.MAP);
        assertThat(propsSchema.getValueType().getType()).isEqualTo(Schema.Type.STRING);
    }

    @Test
    void envelopeHasMetadataPlusData() {
        Schema schema = AvroEnvelopeSchema.derive("X", List.of(
                Map.of("name", "x", "type", "string")
        ));
        assertThat(schema.getField("_log_type").schema().getType()).isEqualTo(Schema.Type.STRING);
        assertThat(schema.getField("_log_class").schema().getType()).isEqualTo(Schema.Type.STRING);
        assertThat(schema.getField("_version").schema().getType()).isEqualTo(Schema.Type.STRING);
        assertThat(schema.getField("data").schema().getType()).isEqualTo(Schema.Type.RECORD);
    }
}
