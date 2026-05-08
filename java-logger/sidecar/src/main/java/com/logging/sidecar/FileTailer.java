package com.logging.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Watches a directory of NDJSON files (one per logger/log_type, written by
 * {@link com.logging.sink.FileSink}) and emits new lines as they arrive. The
 * caller decides what to do with each line — typically forward to Kafka, NATS,
 * or another file.
 *
 * Resumes from the last persisted byte offset in the supplied {@link Checkpoint}.
 * Polls on a configurable interval; this is good enough for log delivery and
 * avoids platform-specific file-watch quirks (kqueue/inotify) inside containers.
 *
 * Detects rotation by file shrinkage (active file replaced) and resumes reading
 * from byte zero of the new file in that case. Rotated files (the {@code .N}
 * siblings) are picked up automatically because each filename is a separate
 * tail key in the checkpoint.
 */
public final class FileTailer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FileTailer.class);

    private final Path watchDir;
    private final String fileGlob;
    private final Checkpoint checkpoint;
    private final long pollIntervalMs;
    private final BiConsumer<String, String> lineConsumer; // (fileKey, line)
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FileTailer(Path watchDir,
                      String fileGlob,
                      Checkpoint checkpoint,
                      long pollIntervalMs,
                      BiConsumer<String, String> lineConsumer) {
        this.watchDir = watchDir;
        this.fileGlob = fileGlob;
        this.checkpoint = checkpoint;
        this.pollIntervalMs = pollIntervalMs;
        this.lineConsumer = lineConsumer;
        this.thread = new Thread(this::run, "file-tailer");
        this.thread.setDaemon(true);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread.start();
        }
    }

    private void run() {
        LOG.info("FileTailer watching {} (glob={}, interval={}ms)", watchDir, fileGlob, pollIntervalMs);
        while (running.get()) {
            try {
                pollOnce();
            } catch (Throwable t) {
                LOG.error("FileTailer poll failed", t);
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** One poll cycle — visible so tests can drive without a thread. */
    public void pollOnce() throws IOException {
        if (!Files.isDirectory(watchDir)) return;
        List<Path> files = listFiles();
        for (Path file : files) {
            tailFile(file);
        }
    }

    private List<Path> listFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(watchDir, fileGlob)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) out.add(p);
            }
        }
        return out;
    }

    private void tailFile(Path file) throws IOException {
        String key = file.getFileName().toString();
        long offset = checkpoint.offsetFor(key);
        long size = Files.size(file);
        if (size < offset) {
            // File rotated/truncated — start over.
            LOG.info("FileTailer detected rotation/truncation on {} (size {} < checkpoint {}), resetting offset",
                    key, size, offset);
            offset = 0L;
        }
        if (size == offset) return;

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            byte[] buffer = new byte[(int) Math.min(size - offset, 1 << 20)]; // up to 1MB per poll
            int read;
            StringBuilder pending = new StringBuilder();
            long pos = offset;
            while ((read = raf.read(buffer)) > 0) {
                String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                pending.append(chunk);
                int newlineIdx;
                while ((newlineIdx = pending.indexOf("\n")) >= 0) {
                    String line = pending.substring(0, newlineIdx);
                    pending.delete(0, newlineIdx + 1);
                    if (!line.isEmpty()) {
                        lineConsumer.accept(key, line);
                    }
                    pos += newlineIdx + 1; // include newline
                }
                if (raf.getFilePointer() >= size) break;
            }
            // Persist offset only at fully-consumed line boundaries (pending text isn't shipped yet).
            checkpoint.update(key, pos);
        }
    }

    @Override
    public void close() {
        running.set(false);
        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
