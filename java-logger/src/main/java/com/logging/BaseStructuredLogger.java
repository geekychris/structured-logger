package com.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Base class for structured logging to Kafka.
 * Provides common functionality for serialization and publishing.
 */
public abstract class BaseStructuredLogger implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BaseStructuredLogger.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    protected final String topicName;
    protected final KafkaProducer<String, String> producer;
    private final String loggerName;
    private final String logType;
    private final String version;

    /**
     * Constructor for base structured logger.
     *
     * @param topicName Kafka topic to publish to
     * @param loggerName Name of this logger for identification
     * @param logType Log type identifier for routing (e.g., "user_activity")
     * @param version Schema version
     * @param kafkaBootstrapServers Kafka bootstrap servers
     */
    protected BaseStructuredLogger(String topicName, String loggerName, String logType, String version, String kafkaBootstrapServers) {
        this.topicName = topicName;
        this.loggerName = loggerName;
        this.logType = logType;
        this.version = version;
        this.producer = createProducer(kafkaBootstrapServers);
    }

    /**
     * Constructor that reads Kafka bootstrap servers from environment variable.
     */
    protected BaseStructuredLogger(String topicName, String loggerName, String logType, String version) {
        this(topicName, loggerName, logType, version,
             System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
    }

    private KafkaProducer<String, String> createProducer(String bootstrapServers) {
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

    /**
     * Publish a log record to Kafka.
     *
     * @param key Partition key (typically user_id or similar)
     * @param logRecord The log record object to publish
     */
    protected void publish(String key, Object logRecord) {
        try {
            String jsonValue = OBJECT_MAPPER.writeValueAsString(logRecord);
            ProducerRecord<String, String> record = new ProducerRecord<>(topicName, key, jsonValue);
            
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    LOG.error("Failed to publish {} log record to topic {}", 
                             loggerName, topicName, exception);
                } else {
                    LOG.debug("Published {} log record to topic {} partition {} offset {}", 
                             loggerName, topicName, metadata.partition(), metadata.offset());
                }
            });
        } catch (Exception e) {
            LOG.error("Error serializing {} log record", loggerName, e);
        }
    }

    /**
     * Publish with async callback.
     */
    protected void publish(String key, Object logRecord, java.util.function.BiConsumer<Boolean, Exception> callback) {
        try {
            String jsonValue = OBJECT_MAPPER.writeValueAsString(logRecord);
            ProducerRecord<String, String> record = new ProducerRecord<>(topicName, key, jsonValue);
            
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    LOG.error("Failed to publish {} log record", loggerName, exception);
                    callback.accept(false, exception);
                } else {
                    LOG.debug("Published {} log record successfully", loggerName);
                    callback.accept(true, null);
                }
            });
        } catch (Exception e) {
            LOG.error("Error serializing {} log record", loggerName, e);
            callback.accept(false, e);
        }
    }

    /**
     * Helper to get current timestamp.
     */
    protected Instant now() {
        return Instant.now();
    }

    /**
     * Flush pending messages and close the producer.
     */
    @Override
    public void close() {
        try {
            producer.flush();
            producer.close(Duration.ofSeconds(5));
            LOG.info("Closed {} logger", loggerName);
        } catch (Exception e) {
            LOG.error("Error closing {} logger", loggerName, e);
        }
    }

    /**
     * Flush all pending messages.
     */
    public void flush() {
        producer.flush();
    }
}
