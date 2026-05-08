package com.logging.sink;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.NatsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Publishes envelopes as NATS JetStream messages. JetStream gives us at-least-once
 * delivery with persistent storage, durable consumers, and replay — a lighter
 * alternative to Kafka for many workloads, especially edge / on-prem and any
 * place where running ZooKeeper/Kraft is overkill.
 *
 * Subject naming convention: {@code subjectPrefix.<log_type>}. A stream that
 * subscribes to {@code subjectPrefix.>} will catch every log type.
 *
 * NATS is an optional dependency on core; consumers must add jnats explicitly
 * when wiring this sink.
 */
public final class NatsJetStreamSink implements LogSink {

    private static final Logger LOG = LoggerFactory.getLogger(NatsJetStreamSink.class);

    private final Connection connection;
    private final JetStream jetStream;
    private final String subjectPrefix;
    private final boolean ownsConnection;
    private final String name;

    public NatsJetStreamSink(String serverUrl, String subjectPrefix) {
        this(connect(serverUrl), subjectPrefix, true);
    }

    public NatsJetStreamSink(Connection connection, String subjectPrefix) {
        this(connection, subjectPrefix, false);
    }

    private NatsJetStreamSink(Connection connection, String subjectPrefix, boolean ownsConnection) {
        this.connection = connection;
        this.subjectPrefix = subjectPrefix;
        this.ownsConnection = ownsConnection;
        try {
            this.jetStream = connection.jetStream();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to acquire JetStream context", e);
        }
        this.name = "nats(" + subjectPrefix + ")";
    }

    private static Connection connect(String serverUrl) {
        try {
            Options options = new Options.Builder()
                    .server(serverUrl)
                    .connectionName("structured-logger")
                    .build();
            return Nats.connect(options);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NATS connect failed: " + serverUrl, e);
        }
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
        String subject = subjectPrefix + "." + envelope.getLogType();
        NatsMessage msg = NatsMessage.builder()
                .subject(subject)
                .data(json.getBytes(StandardCharsets.UTF_8))
                .build();
        CompletableFuture<PublishAck> future = jetStream.publishAsync(msg);
        future.whenComplete((ack, err) -> {
            if (err != null) {
                LOG.error("NATS JetStream publish failed: subject={}", subject, err);
                if (callback != null) callback.accept(false, err);
            } else {
                if (callback != null) callback.accept(true, null);
            }
        });
    }

    @Override
    public void flush() {
        try {
            connection.flush(java.time.Duration.ofSeconds(5));
        } catch (Throwable t) {
            LOG.warn("NATS flush failed", t);
        }
    }

    @Override
    public void close() {
        flush();
        if (ownsConnection) {
            try {
                connection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                LOG.warn("NATS close failed", t);
            }
        }
    }
}
