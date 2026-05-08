package com.logging.sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * Append-only NDJSON sink. One JSON envelope per line; each line is
 * self-describing thanks to the embedded `_log_type` so a sidecar tailer can
 * route mixed records.
 *
 * Designed as the "fast local" path for the sidecar pattern: the application
 * pays only the cost of a buffered file write, and a separate process is
 * responsible for shipping bytes to Kafka, NATS JetStream, or object storage.
 *
 * Rotation by size is supported; when the active file exceeds {@code rotateBytes}
 * it is renamed with a {@code .N} suffix and a fresh file is started.
 */
public final class FileSink implements LogSink {

    private static final Logger LOG = LoggerFactory.getLogger(FileSink.class);

    private final Path activeFile;
    private final long rotateBytes;
    private final boolean fsyncOnFlush;
    private final ReentrantLock lock = new ReentrantLock();

    private BufferedWriter writer;
    private long bytesWritten;
    private int rotationIndex;
    private volatile boolean closed;

    public FileSink(Path activeFile) {
        this(activeFile, 64L * 1024L * 1024L, false);
    }

    public FileSink(Path activeFile, long rotateBytes, boolean fsyncOnFlush) {
        this.activeFile = activeFile;
        this.rotateBytes = rotateBytes;
        this.fsyncOnFlush = fsyncOnFlush;
        try {
            if (activeFile.getParent() != null) {
                Files.createDirectories(activeFile.getParent());
            }
            this.writer = openWriter();
            this.bytesWritten = Files.exists(activeFile) ? Files.size(activeFile) : 0L;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open file sink at " + activeFile, e);
        }
    }

    private BufferedWriter openWriter() throws IOException {
        return Files.newBufferedWriter(
                activeFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    @Override
    public String name() {
        return "file(" + activeFile + ")";
    }

    @Override
    public void publish(LogEnvelope envelope, BiConsumer<Boolean, Throwable> callback) {
        if (closed) {
            if (callback != null) callback.accept(false, new IllegalStateException("FileSink is closed"));
            return;
        }
        String line;
        try {
            line = EnvelopeSerializer.toJson(envelope);
        } catch (Throwable t) {
            if (callback != null) callback.accept(false, t);
            return;
        }
        lock.lock();
        try {
            writer.write(line);
            writer.newLine();
            bytesWritten += line.length() + 1;
            if (rotateBytes > 0 && bytesWritten >= rotateBytes) {
                rotate();
            }
            if (callback != null) callback.accept(true, null);
        } catch (IOException e) {
            LOG.error("FileSink write failed", e);
            if (callback != null) callback.accept(false, e);
        } finally {
            lock.unlock();
        }
    }

    private void rotate() throws IOException {
        writer.flush();
        writer.close();
        rotationIndex++;
        Path rotated = activeFile.resolveSibling(activeFile.getFileName() + "." + rotationIndex);
        Files.move(activeFile, rotated);
        writer = openWriter();
        bytesWritten = 0L;
        LOG.info("FileSink rotated active file -> {}", rotated);
    }

    @Override
    public void flush() {
        if (closed) return;
        lock.lock();
        try {
            writer.flush();
            if (fsyncOnFlush) {
                // Best-effort fsync via re-opening with sync option is too expensive;
                // a Channel-level force is preferable but jumping through Channels here
                // would complicate the buffer ownership. Callers who need durable
                // fsync should use a flat-file-with-fsync transport.
            }
        } catch (IOException e) {
            LOG.error("FileSink flush failed", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        lock.lock();
        try {
            closed = true;
            writer.flush();
            writer.close();
        } catch (IOException e) {
            LOG.error("FileSink close failed", e);
        } finally {
            lock.unlock();
        }
    }

    /** Visible for tests. */
    public Path activeFile() {
        return activeFile;
    }
}
