package com.logging.generated;

import com.logging.BaseStructuredLogger;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Generated structured logger for Tracks user interaction events in the application.
 * 
 * Version: 1.0.0
 * Kafka Topic: user-events
 * Warehouse Table: analytics_logs.user_events
 * 
 * DO NOT EDIT - This file is auto-generated from the log config.
 */
public class UserEventsLogger extends BaseStructuredLogger {

    private static final String TOPIC_NAME = "user-events";
    private static final String LOGGER_NAME = "UserEvents";
    private static final String LOG_TYPE = "user_events";
    private static final String VERSION = "1.0.0";

    public UserEventsLogger() {
        super(TOPIC_NAME, LOGGER_NAME, LOG_TYPE, VERSION);
    }

    public UserEventsLogger(String kafkaBootstrapServers) {
        super(TOPIC_NAME, LOGGER_NAME, LOG_TYPE, VERSION, kafkaBootstrapServers);
    }

    /**
     * Log a UserEvents event.
     */
    public void log(Instant timestamp, LocalDate eventDate, String userId, String sessionId, String eventType, String pageUrl, Map<String, String> properties, String deviceType, Long durationMs) {
        LogRecord record = new LogRecord(timestamp, eventDate, userId, sessionId, eventType, pageUrl, properties, deviceType, durationMs);
        publish(userId, record);
    }

    /**
     * Create a builder for constructing log records.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Log record data class.
     */
    public static class LogRecord {
    private final Instant timestamp;
    private final LocalDate eventDate;
    private final String userId;
    private final String sessionId;
    private final String eventType;
    private final String pageUrl;
    private final Map<String, String> properties;
    private final String deviceType;
    private final Long durationMs;

        public LogRecord(Instant timestamp, LocalDate eventDate, String userId, String sessionId, String eventType, String pageUrl, Map<String, String> properties, String deviceType, Long durationMs) {
        this.timestamp = timestamp;
        this.eventDate = eventDate;
        this.userId = userId;
        this.sessionId = sessionId;
        this.eventType = eventType;
        this.pageUrl = pageUrl;
        this.properties = properties;
        this.deviceType = deviceType;
        this.durationMs = durationMs;
        }

        // Getters
        @JsonProperty("timestamp")
        public Instant getTimestamp() { return timestamp; }
        @JsonProperty("event_date")
        public LocalDate getEventDate() { return eventDate; }
        @JsonProperty("user_id")
        public String getUserId() { return userId; }
        @JsonProperty("session_id")
        public String getSessionId() { return sessionId; }
        @JsonProperty("event_type")
        public String getEventType() { return eventType; }
        @JsonProperty("page_url")
        public String getPageUrl() { return pageUrl; }
        @JsonProperty("properties")
        public Map<String, String> getProperties() { return properties; }
        @JsonProperty("device_type")
        public String getDeviceType() { return deviceType; }
        @JsonProperty("duration_ms")
        public Long getDurationMs() { return durationMs; }
    }

    /**
     * Builder for creating log records.
     */
    public static class Builder {
        private Instant timestamp;
        private LocalDate eventDate;
        private String userId;
        private String sessionId;
        private String eventType;
        private String pageUrl;
        private Map<String, String> properties;
        private String deviceType;
        private Long durationMs;

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        public Builder eventDate(LocalDate eventDate) {
            this.eventDate = eventDate;
            return this;
        }
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }
        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }
        public Builder pageUrl(String pageUrl) {
            this.pageUrl = pageUrl;
            return this;
        }
        public Builder properties(Map<String, String> properties) {
            this.properties = properties;
            return this;
        }
        public Builder deviceType(String deviceType) {
            this.deviceType = deviceType;
            return this;
        }
        public Builder durationMs(Long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public LogRecord build() {
            return new LogRecord(timestamp, eventDate, userId, sessionId, eventType, pageUrl, properties, deviceType, durationMs);
        }
    }
}
