package com.logging.sink;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeSinkTest {

    private final LogEnvelope envelope = new LogEnvelope("k", "t", "C", "1", "data");

    @Test
    void fansOutToAllChildren() {
        RecordingSink a = new RecordingSink();
        RecordingSink b = new RecordingSink();
        try (CompositeSink composite = new CompositeSink(a, b)) {
            AtomicReference<Boolean> ok = new AtomicReference<>();
            composite.publish(envelope, (s, e) -> ok.set(s));
            assertThat(a.records()).hasSize(1);
            assertThat(b.records()).hasSize(1);
            assertThat(ok.get()).isTrue();
        }
        assertThat(a.closeCount()).isEqualTo(1);
        assertThat(b.closeCount()).isEqualTo(1);
    }

    @Test
    void reportsFailureButStillTeesToHealthyChildren() {
        RecordingSink healthy = new RecordingSink();
        RecordingSink broken = new RecordingSink(true);
        AtomicReference<Boolean> overall = new AtomicReference<>();

        try (CompositeSink composite = new CompositeSink(broken, healthy)) {
            composite.publish(envelope, (s, e) -> overall.set(s));
            assertThat(healthy.records()).hasSize(1);
            assertThat(overall.get()).isFalse();
        }
    }

    @Test
    void rejectsEmptyChildList() {
        assertThatThrownBy(() -> new CompositeSink())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void flushPropagatesToChildren() {
        RecordingSink a = new RecordingSink();
        RecordingSink b = new RecordingSink();
        try (CompositeSink composite = new CompositeSink(a, b)) {
            composite.flush();
            assertThat(a.flushCount()).isEqualTo(1);
            assertThat(b.flushCount()).isEqualTo(1);
        }
    }
}
