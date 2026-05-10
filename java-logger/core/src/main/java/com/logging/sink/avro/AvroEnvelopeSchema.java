package com.logging.sink.avro;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;

import java.util.List;
import java.util.Map;

/**
 * Derives an Avro schema for a {@link com.logging.sink.LogEnvelope} from the
 * field list expressed in a JSON log config — same field shape as the
 * {@code generators/} input.
 *
 * Mirror of the Python derive_avro_schema(); this is the source of truth on
 * the JVM side. Field-type mapping matches the Python side exactly so the
 * registered schema is interchangeable.
 */
public final class AvroEnvelopeSchema {

    private AvroEnvelopeSchema() {}

    public static Schema derive(String logClass, List<Map<String, Object>> fields) {
        SchemaBuilder.FieldAssembler<Schema> data = SchemaBuilder
                .record(logClass + "Data")
                .namespace("structured_logging")
                .fields();
        for (Map<String, Object> f : fields) {
            String name = (String) f.get("name");
            String type = (String) f.get("type");
            boolean required = !Boolean.FALSE.equals(f.getOrDefault("required", true));
            Schema fieldSchema = avroTypeOf(type);
            if (required) {
                data = data.name(name).type(fieldSchema).noDefault();
            } else {
                Schema nullable = SchemaBuilder.unionOf().nullType().and().type(fieldSchema).endUnion();
                data = data.name(name).type(nullable).withDefault(null);
            }
        }
        Schema dataSchema = data.endRecord();

        return SchemaBuilder
                .record(logClass + "Envelope")
                .namespace("structured_logging")
                .fields()
                .requiredString("_log_type")
                .requiredString("_log_class")
                .requiredString("_version")
                .name("data").type(dataSchema).noDefault()
                .endRecord();
    }

    private static Schema avroTypeOf(String type) {
        switch (type) {
            case "string":
            case "timestamp":
            case "date":
                return SchemaBuilder.builder().stringType();
            case "int":
                return SchemaBuilder.builder().intType();
            case "long":
                return SchemaBuilder.builder().longType();
            case "float":
                return SchemaBuilder.builder().floatType();
            case "double":
                return SchemaBuilder.builder().doubleType();
            case "boolean":
                return SchemaBuilder.builder().booleanType();
            case "array<string>":
                return SchemaBuilder.array().items().stringType();
            case "array<int>":
                return SchemaBuilder.array().items().intType();
            case "array<long>":
                return SchemaBuilder.array().items().longType();
            case "map<string,string>":
                return SchemaBuilder.map().values().stringType();
            default:
                // fallback: treat unknown as string
                return SchemaBuilder.builder().stringType();
        }
    }
}
