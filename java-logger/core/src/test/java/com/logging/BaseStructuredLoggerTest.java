package com.logging;

import com.logging.sink.LogEnvelope;
import com.logging.sink.RecordingSink;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaseStructuredLoggerTest {

    /** Concrete subclass that exposes publish() so we can drive the base class from a test. */
    static final class TestLogger extends BaseStructuredLogger {
        TestLogger(RecordingSink sink) {
            super("test-topic", "TestLogger", "test_events", "1.0.0", sink);
        }
        void send(String userId, Map<String, Object> data) { publish(userId, data); }
    }

    @Test
    void wrapsRecordsInEnvelopeWithRoutingMetadata() {
        RecordingSink sink = new RecordingSink();
        try (TestLogger logger = new TestLogger(sink)) {
            logger.send("u1", Map.of("user_id", "u1", "amount", 42));
        }
        assertThat(sink.records()).hasSize(1);
        LogEnvelope env = sink.records().get(0);
        assertThat(env.getKey()).isEqualTo("u1");
        assertThat(env.getLogType()).isEqualTo("test_events");
        assertThat(env.getLogClass()).isEqualTo("TestLogger");
        assertThat(env.getVersion()).isEqualTo("1.0.0");
        assertThat(env.getData()).isEqualTo(Map.of("user_id", "u1", "amount", 42));
    }

    @Test
    void doesNotCloseInjectedSink() {
        // Injected sinks (the LogSink-accepting ctor) are caller-owned; the
        // logger should still flush but NOT close.
        RecordingSink sink = new RecordingSink();
        try (TestLogger logger = new TestLogger(sink)) {
            logger.send("u1", Map.of("k", "v"));
        }
        assertThat(sink.flushCount()).isGreaterThanOrEqualTo(1);
        assertThat(sink.closeCount()).isZero();
    }
}
