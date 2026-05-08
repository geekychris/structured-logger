package com.logging.sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Fans an envelope out to multiple sinks. Used to satisfy "log to Kafka and
 * regular logging at the same time" — a Slf4jSink + KafkaSink combo, for
 * example.
 *
 * The aggregated callback fires once with success only when every child sink
 * has succeeded; the first failure short-circuits to a failure callback.
 * Children are still invoked even if a previous child failed (the "tee"
 * semantic) — we don't want a Kafka outage to silence local logging.
 */
public final class CompositeSink implements LogSink {

    private static final Logger LOG = LoggerFactory.getLogger(CompositeSink.class);

    private final List<LogSink> children;
    private final String name;

    public CompositeSink(LogSink... children) {
        this(Arrays.asList(children));
    }

    public CompositeSink(List<LogSink> children) {
        if (children == null || children.isEmpty()) {
            throw new IllegalArgumentException("CompositeSink requires at least one child sink");
        }
        this.children = new ArrayList<>(children);
        this.name = "composite(" + children.stream().map(LogSink::name).collect(Collectors.joining(",")) + ")";
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void publish(LogEnvelope envelope, BiConsumer<Boolean, Throwable> callback) {
        AtomicInteger remaining = new AtomicInteger(children.size());
        AtomicInteger failures = new AtomicInteger(0);
        for (LogSink child : children) {
            try {
                child.publish(envelope, (ok, err) -> {
                    if (Boolean.FALSE.equals(ok)) {
                        failures.incrementAndGet();
                        LOG.warn("Composite child sink {} failed", child.name(), err);
                    }
                    if (remaining.decrementAndGet() == 0 && callback != null) {
                        callback.accept(failures.get() == 0, null);
                    }
                });
            } catch (Throwable t) {
                LOG.error("Composite child sink {} threw synchronously", child.name(), t);
                failures.incrementAndGet();
                if (remaining.decrementAndGet() == 0 && callback != null) {
                    callback.accept(false, t);
                }
            }
        }
    }

    @Override
    public void flush() {
        for (LogSink child : children) {
            try {
                child.flush();
            } catch (Throwable t) {
                LOG.warn("Composite child sink {} flush failed", child.name(), t);
            }
        }
    }

    @Override
    public void close() {
        for (LogSink child : children) {
            try {
                child.close();
            } catch (Throwable t) {
                LOG.warn("Composite child sink {} close failed", child.name(), t);
            }
        }
    }

    /** Visible for tests. */
    public List<LogSink> children() {
        return children;
    }
}
