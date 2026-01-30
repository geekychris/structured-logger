package com.logging.generated;

import com.logging.BaseStructuredLogger;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Generated structured logger for API endpoint performance and usage metrics.
 * 
 * Version: 1.0.0
 * Kafka Topic: api-metrics
 * Warehouse Table: analytics.logs.api_metrics
 * 
 * DO NOT EDIT - This file is auto-generated from the log config.
 */
public class ApiMetricsLogger extends BaseStructuredLogger {

    private static final String TOPIC_NAME = "api-metrics";
    private static final String LOGGER_NAME = "ApiMetrics";

    public ApiMetricsLogger() {
        super(TOPIC_NAME, LOGGER_NAME);
    }

    public ApiMetricsLogger(String kafkaBootstrapServers) {
        super(TOPIC_NAME, LOGGER_NAME, kafkaBootstrapServers);
    }

    /**
     * Log a ApiMetrics event.
     */
    public void log(Instant timestamp, LocalDate metricDate, String serviceName, String endpoint, String method, Integer statusCode, Long responseTimeMs, Long requestSizeBytes, Long responseSizeBytes, String userId, String clientIp, String errorMessage) {
        LogRecord record = new LogRecord(timestamp, metricDate, serviceName, endpoint, method, statusCode, responseTimeMs, requestSizeBytes, responseSizeBytes, userId, clientIp, errorMessage);
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
    private final LocalDate metricDate;
    private final String serviceName;
    private final String endpoint;
    private final String method;
    private final Integer statusCode;
    private final Long responseTimeMs;
    private final Long requestSizeBytes;
    private final Long responseSizeBytes;
    private final String userId;
    private final String clientIp;
    private final String errorMessage;

        public LogRecord(Instant timestamp, LocalDate metricDate, String serviceName, String endpoint, String method, Integer statusCode, Long responseTimeMs, Long requestSizeBytes, Long responseSizeBytes, String userId, String clientIp, String errorMessage) {
        this.timestamp = timestamp;
        this.metricDate = metricDate;
        this.serviceName = serviceName;
        this.endpoint = endpoint;
        this.method = method;
        this.statusCode = statusCode;
        this.responseTimeMs = responseTimeMs;
        this.requestSizeBytes = requestSizeBytes;
        this.responseSizeBytes = responseSizeBytes;
        this.userId = userId;
        this.clientIp = clientIp;
        this.errorMessage = errorMessage;
        }

        // Getters
        @JsonProperty("timestamp")
        public Instant getTimestamp() { return timestamp; }
        @JsonProperty("metric_date")
        public LocalDate getMetricDate() { return metricDate; }
        @JsonProperty("service_name")
        public String getServiceName() { return serviceName; }
        @JsonProperty("endpoint")
        public String getEndpoint() { return endpoint; }
        @JsonProperty("method")
        public String getMethod() { return method; }
        @JsonProperty("status_code")
        public Integer getStatusCode() { return statusCode; }
        @JsonProperty("response_time_ms")
        public Long getResponseTimeMs() { return responseTimeMs; }
        @JsonProperty("request_size_bytes")
        public Long getRequestSizeBytes() { return requestSizeBytes; }
        @JsonProperty("response_size_bytes")
        public Long getResponseSizeBytes() { return responseSizeBytes; }
        @JsonProperty("user_id")
        public String getUserId() { return userId; }
        @JsonProperty("client_ip")
        public String getClientIp() { return clientIp; }
        @JsonProperty("error_message")
        public String getErrorMessage() { return errorMessage; }
    }

    /**
     * Builder for creating log records.
     */
    public static class Builder {
        private Instant timestamp;
        private LocalDate metricDate;
        private String serviceName;
        private String endpoint;
        private String method;
        private Integer statusCode;
        private Long responseTimeMs;
        private Long requestSizeBytes;
        private Long responseSizeBytes;
        private String userId;
        private String clientIp;
        private String errorMessage;

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        public Builder metricDate(LocalDate metricDate) {
            this.metricDate = metricDate;
            return this;
        }
        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }
        public Builder method(String method) {
            this.method = method;
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
        public Builder requestSizeBytes(Long requestSizeBytes) {
            this.requestSizeBytes = requestSizeBytes;
            return this;
        }
        public Builder responseSizeBytes(Long responseSizeBytes) {
            this.responseSizeBytes = responseSizeBytes;
            return this;
        }
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }
        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public LogRecord build() {
            return new LogRecord(timestamp, metricDate, serviceName, endpoint, method, statusCode, responseTimeMs, requestSizeBytes, responseSizeBytes, userId, clientIp, errorMessage);
        }
    }
}
