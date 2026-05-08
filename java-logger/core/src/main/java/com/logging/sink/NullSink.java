package com.logging.sink;

import java.util.function.BiConsumer;

/**
 * Drops every record. Useful for unit tests of code that has a logger field
 * but where the test doesn't care about delivery.
 */
public final class NullSink implements LogSink {

    @Override
    public String name() {
        return "null";
    }

    @Override
    public void publish(LogEnvelope envelope, BiConsumer<Boolean, Throwable> callback) {
        if (callback != null) {
            callback.accept(true, null);
        }
    }

    @Override
    public void flush() {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}
