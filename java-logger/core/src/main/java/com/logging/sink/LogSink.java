package com.logging.sink;

import java.util.function.BiConsumer;

/**
 * Pluggable destination for log envelopes.
 *
 * Implementations may deliver synchronously or asynchronously; the contract is that
 * {@link #publish(LogEnvelope, BiConsumer)} returns quickly and the callback is
 * invoked exactly once when the record is durably handed off (success) or a delivery
 * attempt fails permanently (failure).
 *
 * Sinks are expected to be thread-safe and reusable across multiple loggers; callers
 * own their lifecycle via {@link #close()}.
 */
public interface LogSink extends AutoCloseable {

    /** Stable identifier — useful for logs, metrics, and tests. */
    String name();

    /**
     * Publish an envelope. The callback receives {@code (true, null)} on success
     * and {@code (false, throwable)} on failure. Implementations must invoke the
     * callback exactly once; passing {@code null} is permitted if the caller does
     * not care about completion.
     */
    void publish(LogEnvelope envelope, BiConsumer<Boolean, Throwable> callback);

    /** Block until any buffered records have been handed off to the underlying transport. */
    void flush();

    @Override
    void close();
}
