package com.logging.sink;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Properties;
import java.util.function.BiConsumer;

/**
 * Publishes envelopes to a Kafka topic. Produces JSON values with a string key
 * (typically user_id or session_id) using snappy compression and the same
 * batching defaults the original {@link com.logging.BaseStructuredLogger} used.
 *
 * Each KafkaSink owns a single shared {@link KafkaProducer}; reuse one sink
 * across many loggers if they target the same broker cluster.
 */
public final class KafkaSink implements LogSink {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaSink.class);

    private final String topic;
    private final Producer<String, String> producer;
    private final boolean ownsProducer;
    private final String name;

    /** Construct a sink owning a fresh producer. */
    public KafkaSink(String topic, String bootstrapServers) {
        this(topic, defaultProducer(bootstrapServers), true);
    }

    /**
     * Construct a sink that delegates to an externally-owned producer. The
     * caller retains lifecycle responsibility — {@link #close()} will not
     * close the producer in this mode.
     */
    public KafkaSink(String topic, Producer<String, String> producer) {
        this(topic, producer, false);
    }

    private KafkaSink(String topic, Producer<String, String> producer, boolean ownsProducer) {
        this.topic = topic;
        this.producer = producer;
        this.ownsProducer = ownsProducer;
        this.name = "kafka(" + topic + ")";
    }

    private static Producer<String, String> defaultProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
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
        String json;
        try {
            json = EnvelopeSerializer.toJson(envelope);
        } catch (Throwable t) {
            if (callback != null) callback.accept(false, t);
            return;
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, envelope.getKey(), json);
        try {
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    LOG.error("Kafka publish failed: topic={} key={}", topic, envelope.getKey(), exception);
                    if (callback != null) callback.accept(false, exception);
                } else {
                    if (callback != null) callback.accept(true, null);
                }
            });
        } catch (Throwable t) {
            LOG.error("Kafka send threw", t);
            if (callback != null) callback.accept(false, t);
        }
    }

    @Override
    public void flush() {
        producer.flush();
    }

    @Override
    public void close() {
        try {
            producer.flush();
        } catch (Throwable t) {
            LOG.warn("KafkaSink flush during close failed", t);
        }
        if (ownsProducer) {
            try {
                producer.close(Duration.ofSeconds(5));
            } catch (Throwable t) {
                LOG.warn("KafkaSink producer close failed", t);
            }
        }
    }
}
