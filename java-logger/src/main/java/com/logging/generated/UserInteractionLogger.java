package com.logging.generated;

import com.logging.BaseStructuredLogger;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Generated structured logger for Tracks all user interactions: clicks, reactions, comments, messages, searches, profile views.
 * 
 * Version: 1.0.0
 * Kafka Topic: worksphere-user-interactions
 * Warehouse Table: analytics.user_interactions
 * 
 * DO NOT EDIT - This file is auto-generated from the log config.
 */
public class UserInteractionLogger extends BaseStructuredLogger {

    private static final String TOPIC_NAME = "worksphere-user-interactions";
    private static final String LOGGER_NAME = "UserInteraction";
    private static final String LOG_TYPE = "user_interaction";
    private static final String VERSION = "1.0.0";

    public UserInteractionLogger() {
        super(TOPIC_NAME, LOGGER_NAME, LOG_TYPE, VERSION);
    }

    public UserInteractionLogger(String kafkaBootstrapServers) {
        super(TOPIC_NAME, LOGGER_NAME, LOG_TYPE, VERSION, kafkaBootstrapServers);
    }

    /**
     * Log a UserInteraction event.
     */
    public void log(Instant timestamp, LocalDate eventDate, Long userId, String interactionType, Long targetId, String targetType, Long contentAuthorId, String reactionType, String searchQuery, Integer searchResultCount, Boolean messageHasAttachment, String botContext, String botToolsUsed, Long botResponseTimeMs, Long groupId, Long pageId, String deviceType, Map<String, String> properties) {
        LogRecord record = new LogRecord(timestamp, eventDate, userId, interactionType, targetId, targetType, contentAuthorId, reactionType, searchQuery, searchResultCount, messageHasAttachment, botContext, botToolsUsed, botResponseTimeMs, groupId, pageId, deviceType, properties);
        publish(String.valueOf(userId), record);
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
    private final Long userId;
    private final String interactionType;
    private final Long targetId;
    private final String targetType;
    private final Long contentAuthorId;
    private final String reactionType;
    private final String searchQuery;
    private final Integer searchResultCount;
    private final Boolean messageHasAttachment;
    private final String botContext;
    private final String botToolsUsed;
    private final Long botResponseTimeMs;
    private final Long groupId;
    private final Long pageId;
    private final String deviceType;
    private final Map<String, String> properties;

        public LogRecord(Instant timestamp, LocalDate eventDate, Long userId, String interactionType, Long targetId, String targetType, Long contentAuthorId, String reactionType, String searchQuery, Integer searchResultCount, Boolean messageHasAttachment, String botContext, String botToolsUsed, Long botResponseTimeMs, Long groupId, Long pageId, String deviceType, Map<String, String> properties) {
        this.timestamp = timestamp;
        this.eventDate = eventDate;
        this.userId = userId;
        this.interactionType = interactionType;
        this.targetId = targetId;
        this.targetType = targetType;
        this.contentAuthorId = contentAuthorId;
        this.reactionType = reactionType;
        this.searchQuery = searchQuery;
        this.searchResultCount = searchResultCount;
        this.messageHasAttachment = messageHasAttachment;
        this.botContext = botContext;
        this.botToolsUsed = botToolsUsed;
        this.botResponseTimeMs = botResponseTimeMs;
        this.groupId = groupId;
        this.pageId = pageId;
        this.deviceType = deviceType;
        this.properties = properties;
        }

        // Getters
        @JsonProperty("timestamp")
        public Instant getTimestamp() { return timestamp; }
        @JsonProperty("event_date")
        public LocalDate getEventDate() { return eventDate; }
        @JsonProperty("user_id")
        public Long getUserId() { return userId; }
        @JsonProperty("interaction_type")
        public String getInteractionType() { return interactionType; }
        @JsonProperty("target_id")
        public Long getTargetId() { return targetId; }
        @JsonProperty("target_type")
        public String getTargetType() { return targetType; }
        @JsonProperty("content_author_id")
        public Long getContentAuthorId() { return contentAuthorId; }
        @JsonProperty("reaction_type")
        public String getReactionType() { return reactionType; }
        @JsonProperty("search_query")
        public String getSearchQuery() { return searchQuery; }
        @JsonProperty("search_result_count")
        public Integer getSearchResultCount() { return searchResultCount; }
        @JsonProperty("message_has_attachment")
        public Boolean getMessageHasAttachment() { return messageHasAttachment; }
        @JsonProperty("bot_context")
        public String getBotContext() { return botContext; }
        @JsonProperty("bot_tools_used")
        public String getBotToolsUsed() { return botToolsUsed; }
        @JsonProperty("bot_response_time_ms")
        public Long getBotResponseTimeMs() { return botResponseTimeMs; }
        @JsonProperty("group_id")
        public Long getGroupId() { return groupId; }
        @JsonProperty("page_id")
        public Long getPageId() { return pageId; }
        @JsonProperty("device_type")
        public String getDeviceType() { return deviceType; }
        @JsonProperty("properties")
        public Map<String, String> getProperties() { return properties; }
    }

    /**
     * Builder for creating log records.
     */
    public static class Builder {
        private Instant timestamp;
        private LocalDate eventDate;
        private Long userId;
        private String interactionType;
        private Long targetId;
        private String targetType;
        private Long contentAuthorId;
        private String reactionType;
        private String searchQuery;
        private Integer searchResultCount;
        private Boolean messageHasAttachment;
        private String botContext;
        private String botToolsUsed;
        private Long botResponseTimeMs;
        private Long groupId;
        private Long pageId;
        private String deviceType;
        private Map<String, String> properties;

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        public Builder eventDate(LocalDate eventDate) {
            this.eventDate = eventDate;
            return this;
        }
        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }
        public Builder interactionType(String interactionType) {
            this.interactionType = interactionType;
            return this;
        }
        public Builder targetId(Long targetId) {
            this.targetId = targetId;
            return this;
        }
        public Builder targetType(String targetType) {
            this.targetType = targetType;
            return this;
        }
        public Builder contentAuthorId(Long contentAuthorId) {
            this.contentAuthorId = contentAuthorId;
            return this;
        }
        public Builder reactionType(String reactionType) {
            this.reactionType = reactionType;
            return this;
        }
        public Builder searchQuery(String searchQuery) {
            this.searchQuery = searchQuery;
            return this;
        }
        public Builder searchResultCount(Integer searchResultCount) {
            this.searchResultCount = searchResultCount;
            return this;
        }
        public Builder messageHasAttachment(Boolean messageHasAttachment) {
            this.messageHasAttachment = messageHasAttachment;
            return this;
        }
        public Builder botContext(String botContext) {
            this.botContext = botContext;
            return this;
        }
        public Builder botToolsUsed(String botToolsUsed) {
            this.botToolsUsed = botToolsUsed;
            return this;
        }
        public Builder botResponseTimeMs(Long botResponseTimeMs) {
            this.botResponseTimeMs = botResponseTimeMs;
            return this;
        }
        public Builder groupId(Long groupId) {
            this.groupId = groupId;
            return this;
        }
        public Builder pageId(Long pageId) {
            this.pageId = pageId;
            return this;
        }
        public Builder deviceType(String deviceType) {
            this.deviceType = deviceType;
            return this;
        }
        public Builder properties(Map<String, String> properties) {
            this.properties = properties;
            return this;
        }

        public LogRecord build() {
            return new LogRecord(timestamp, eventDate, userId, interactionType, targetId, targetType, contentAuthorId, reactionType, searchQuery, searchResultCount, messageHasAttachment, botContext, botToolsUsed, botResponseTimeMs, groupId, pageId, deviceType, properties);
        }
    }
}
