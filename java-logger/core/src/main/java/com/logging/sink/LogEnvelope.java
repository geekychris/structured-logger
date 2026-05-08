package com.logging.sink;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Routing envelope wrapped around every log record by {@link com.logging.BaseStructuredLogger}
 * before it is handed to a {@link LogSink}.
 *
 * The same envelope shape (`_log_type`, `_log_class`, `_version`, `data`) is serialized
 * regardless of which sink ultimately delivers the message, which is what lets the sidecar
 * and stream-processor route messages by `_log_type` after the fact.
 */
public final class LogEnvelope {

    private final String key;
    private final String logType;
    private final String logClass;
    private final String version;
    private final Object data;

    public LogEnvelope(String key, String logType, String logClass, String version, Object data) {
        this.key = key;
        this.logType = Objects.requireNonNull(logType, "logType");
        this.logClass = Objects.requireNonNull(logClass, "logClass");
        this.version = Objects.requireNonNull(version, "version");
        this.data = data;
    }

    /** Partition / routing key (typically user_id or session_id). May be null. */
    public String getKey() {
        return key;
    }

    @JsonProperty("_log_type")
    public String getLogType() {
        return logType;
    }

    @JsonProperty("_log_class")
    public String getLogClass() {
        return logClass;
    }

    @JsonProperty("_version")
    public String getVersion() {
        return version;
    }

    @JsonProperty("data")
    public Object getData() {
        return data;
    }

    /** Returns the serializable map shape (keeps ordering predictable for tests). */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("_log_type", logType);
        map.put("_log_class", logClass);
        map.put("_version", version);
        map.put("data", data);
        return map;
    }
}
