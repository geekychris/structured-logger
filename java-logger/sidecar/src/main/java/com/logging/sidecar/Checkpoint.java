package com.logging.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-file byte-offset checkpoint, persisted as JSON. The sidecar updates this
 * after every successful batch so a restart can resume the tail from the last
 * known good position rather than re-shipping the file from byte zero.
 *
 * Writes go through a tmp + atomic rename to avoid leaving a half-written file
 * if the process is killed mid-flush.
 */
public final class Checkpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;
    private final Map<String, Long> offsets = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public Checkpoint(Path path) {
        this.path = path;
        load();
    }

    private void load() {
        if (!Files.exists(path)) return;
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = MAPPER.readValue(bytes, Map.class);
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                offsets.put(e.getKey(), ((Number) e.getValue()).longValue());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load checkpoint at " + path, e);
        }
    }

    public long offsetFor(String fileKey) {
        lock.lock();
        try {
            return offsets.getOrDefault(fileKey, 0L);
        } finally {
            lock.unlock();
        }
    }

    public void update(String fileKey, long offset) {
        lock.lock();
        try {
            offsets.put(fileKey, offset);
            persist();
        } finally {
            lock.unlock();
        }
    }

    private void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            byte[] payload = MAPPER.writeValueAsBytes(offsets);
            Files.write(tmp, payload);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist checkpoint at " + path, e);
        }
    }

    public Map<String, Long> snapshot() {
        lock.lock();
        try {
            return Collections.unmodifiableMap(new LinkedHashMap<>(offsets));
        } finally {
            lock.unlock();
        }
    }
}
