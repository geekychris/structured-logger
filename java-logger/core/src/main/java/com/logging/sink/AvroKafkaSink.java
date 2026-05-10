package com.logging.sink;

import com.logging.sink.avro.AvroEnvelopeSchema;
import com.logging.sink.avro.SchemaRegistryClient;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;

/**
 * Kafka sink that encodes each envelope as Avro using the Confluent wire format
 * (`[0x00][4-byte schema id][avro binary]`). Registers the schema with Confluent
 * Schema Registry at construction time.
 *
 * Lazily depends on org.apache.avro:avro — only loaded if you instantiate this
 * sink. {@link com.logging.sink.KafkaSink} (JSON) remains the default.
 */
public final class AvroKafkaSink implements LogSink {

    private static final Logger LOG = LoggerFactory.getLogger(AvroKafkaSink.class);

    private final String topic;
    private final Schema schema;
    private final int schemaId;
    private final Producer<String, byte[]> producer;
    private final boolean ownsProducer;
    private final String name;
    private final DatumWriter<GenericRecord> writer;

    public AvroKafkaSink(
            String topic,
            String logClass,
            List<Map<String, Object>> fields,
            String schemaRegistryUrl,
            String bootstrapServers) {
        this(topic, AvroEnvelopeSchema.derive(logClass, fields),
                schemaRegistryUrl, defaultProducer(bootstrapServers), true);
    }

    public AvroKafkaSink(
            String topic,
            Schema envelopeSchema,
            String schemaRegistryUrl,
            Producer<String, byte[]> producer,
            boolean ownsProducer) {
        this.topic = topic;
        this.schema = envelopeSchema;
        SchemaRegistryClient sr = new SchemaRegistryClient(schemaRegistryUrl);
        this.schemaId = sr.register(topic + "-value", envelopeSchema.toString());
        LOG.info("AvroKafkaSink registered schema id={} for subject={}-value", schemaId, topic);
        this.producer = producer;
        this.ownsProducer = ownsProducer;
        this.name = "kafka_avro(" + topic + ", sr_id=" + schemaId + ")";
        this.writer = new SpecificDatumWriter<>(envelopeSchema);
    }

    private static Producer<String, byte[]> defaultProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        return new KafkaProducer<>(props);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void publish(LogEnvelope envelope, BiConsumer<Boolean, Throwable> callback) {
        byte[] payload;
        try {
            payload = encode(envelope);
        } catch (Throwable t) {
            LOG.error("Avro encode failed for topic={}", topic, t);
            if (callback != null) callback.accept(false, t);
            return;
        }
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, envelope.getKey(), payload);
        try {
            producer.send(record, (md, ex) -> {
                if (ex != null) {
                    LOG.error("Avro Kafka publish failed: topic={} key={}", topic, envelope.getKey(), ex);
                    if (callback != null) callback.accept(false, ex);
                } else if (callback != null) {
                    callback.accept(true, null);
                }
            });
        } catch (Throwable t) {
            LOG.error("Avro Kafka send threw", t);
            if (callback != null) callback.accept(false, t);
        }
    }

    private byte[] encode(LogEnvelope envelope) throws Exception {
        // Build a GenericRecord that conforms to the registered envelope schema.
        Schema dataSchema = schema.getField("data").schema();
        GenericRecord dataRec = new GenericData.Record(dataSchema);
        Object dataObj = envelope.toMap().get("data");
        if (dataObj instanceof Map) {
            Map<?, ?> dataMap = (Map<?, ?>) dataObj;
            for (Schema.Field f : dataSchema.getFields()) {
                Object v = dataMap.get(f.name());
                dataRec.put(f.name(), v);
            }
        }
        GenericRecord envRec = new GenericData.Record(schema);
        envRec.put("_log_type", envelope.getLogType());
        envRec.put("_log_class", envelope.getLogClass());
        envRec.put("_version", envelope.getVersion());
        envRec.put("data", dataRec);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        // Confluent wire format
        baos.write(0x00);
        baos.write(ByteBuffer.allocate(4).putInt(schemaId).array());
        BinaryEncoder enc = EncoderFactory.get().binaryEncoder(baos, null);
        writer.write(envRec, enc);
        enc.flush();
        return baos.toByteArray();
    }

    @Override
    public void flush() {
        producer.flush();
    }

    @Override
    public void close() {
        try { producer.flush(); } catch (Throwable t) { LOG.warn("flush during close failed", t); }
        if (ownsProducer) {
            try { producer.close(Duration.ofSeconds(5)); } catch (Throwable t) { LOG.warn("close failed", t); }
        }
    }
}
