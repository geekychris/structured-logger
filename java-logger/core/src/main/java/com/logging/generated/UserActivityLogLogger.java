package com.logging.generated;

import com.logging.BaseStructuredLogger;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Generated structured logger for Logs user activity events such as login, logout, and API access.
 * 
 * Version: 1.0.0
 * Kafka Topic: user-activity
 * Warehouse Table: analytics_logs.user_activity
 * 
 * DO NOT EDIT - This file is auto-generated from the log config.
 */
public class UserActivityLogLogger extends BaseStructuredLogger {

    private static final String TOPIC_NAME = "user-activity";
    private static final String LOGGER_NAME = "UserActivityLog";
    private static final String LOG_TYPE = "user_activity";
    private static final String VERSION = "1.0.0";

    public UserActivityLogLogger() {
        super(TOPIC_NAME, LOGGER_NAME, LOG_TYPE, VERSION);
    }

    public UserActivityLogLogger(String kafkaBootstrapServers) {
        super(TOPIC_NAME, LOGGER_NAME, LOG_TYPE, VERSION, kafkaBootstrapServers);
    }

    /**
     * Log a UserActivityLog event.
     */
    public void log(String userId, String username, String eventType, LocalDate eventDate, Instant timestamp, String ipAddress, String userAgent, String endpoint, String httpMethod, Integer statusCode, Long responseTimeMs, String sessionId, Map<String, String> metadata) {
        LogRecord record = new LogRecord(userId, username, eventType, eventDate, timestamp, ipAddress, userAgent, endpoint, httpMethod, statusCode, responseTimeMs, sessionId, metadata);
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
    private final String userId;
    private final String username;
    private final String eventType;
    private final LocalDate eventDate;
    private final Instant timestamp;
    private final String ipAddress;
    private final String userAgent;
    private final String endpoint;
    private final String httpMethod;
    private final Integer statusCode;
    private final Long responseTimeMs;
    private final String sessionId;
    private final Map<String, String> metadata;

        public LogRecord(String userId, String username, String eventType, LocalDate eventDate, Instant timestamp, String ipAddress, String userAgent, String endpoint, String httpMethod, Integer statusCode, Long responseTimeMs, String sessionId, Map<String, String> metadata) {
        this.userId = userId;
        this.username = username;
        this.eventType = eventType;
        this.eventDate = eventDate;
        this.timestamp = timestamp;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.endpoint = endpoint;
        this.httpMethod = httpMethod;
        this.statusCode = statusCode;
        this.responseTimeMs = responseTimeMs;
        this.sessionId = sessionId;
        this.metadata = metadata;
        }

        // Getters
        @JsonProperty("user_id")
        public String getUserId() { return userId; }
        @JsonProperty("username")
        public String getUsername() { return username; }
        @JsonProperty("event_type")
        public String getEventType() { return eventType; }
        @JsonProperty("event_date")
        public LocalDate getEventDate() { return eventDate; }
        @JsonProperty("timestamp")
        public Instant getTimestamp() { return timestamp; }
        @JsonProperty("ip_address")
        public String getIpAddress() { return ipAddress; }
        @JsonProperty("user_agent")
        public String getUserAgent() { return userAgent; }
        @JsonProperty("endpoint")
        public String getEndpoint() { return endpoint; }
        @JsonProperty("http_method")
        public String getHttpMethod() { return httpMethod; }
        @JsonProperty("status_code")
        public Integer getStatusCode() { return statusCode; }
        @JsonProperty("response_time_ms")
        public Long getResponseTimeMs() { return responseTimeMs; }
        @JsonProperty("session_id")
        public String getSessionId() { return sessionId; }
        @JsonProperty("metadata")
        public Map<String, String> getMetadata() { return metadata; }
    }

    /**
     * Builder for creating log records.
     */
    public static class Builder {
        private String userId;
        private String username;
        private String eventType;
        private LocalDate eventDate;
        private Instant timestamp;
        private String ipAddress;
        private String userAgent;
        private String endpoint;
        private String httpMethod;
        private Integer statusCode;
        private Long responseTimeMs;
        private String sessionId;
        private Map<String, String> metadata;

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }
        public Builder username(String username) {
            this.username = username;
            return this;
        }
        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }
        public Builder eventDate(LocalDate eventDate) {
            this.eventDate = eventDate;
            return this;
        }
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }
        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }
        public Builder httpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
            return this;
        }
        public Builder statusCode(Integer statusCode) {
            this.statusCode = statusCode;
            return this;
        }
        public Builder responseTimeMs(Long responseTimeMs) {
            this.responseTimeMs = responseTimeMs;
            return this;
        }
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }
        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public LogRecord build() {
            return new LogRecord(userId, username, eventType, eventDate, timestamp, ipAddress, userAgent, endpoint, httpMethod, statusCode, responseTimeMs, sessionId, metadata);
        }
    }
}
