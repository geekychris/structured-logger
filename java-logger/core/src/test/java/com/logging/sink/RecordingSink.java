package com.logging.sink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * In-memory sink that captures every published envelope plus flush/close
 * counts. Used by tests that need to verify routing without spinning up a
 * Kafka or NATS broker.
 */
public final class RecordingSink implements LogSink {

    private final List<LogEnvelope> records = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger flushCount = new AtomicInteger();
    private final AtomicInteger closeCount = new AtomicInteger();
    private final boolean failPublish;

    public RecordingSink() {
        this(false);
    }

    public RecordingSink(boolean failPublish) {
        this.failPublish = failPublish;
    }

    @Override public String name() { return failPublish ? "recording-fail" : "recording"; }

    @Override
    public void publish(LogEnvelope envelope, BiConsumer<Boolean, Throwable> callback) {
        if (failPublish) {
            if (callback != null) callback.accept(false, new RuntimeException("simulated failure"));
            return;
        }
        records.add(envelope);
        if (callback != null) callback.accept(true, null);
    }

    @Override public void flush() { flushCount.incrementAndGet(); }
    @Override public void close() { closeCount.incrementAndGet(); }

    public List<LogEnvelope> records() { return records; }
    public int flushCount() { return flushCount.get(); }
    public int closeCount() { return closeCount.get(); }
}
