package com.logging.generated;

import com.logging.BaseStructuredLogger;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Generated structured logger for Tracks posts shown in user feeds with ranking features.
 * 
 * Version: 1.0.0
 * Kafka Topic: worksphere-feed-impressions
 * Warehouse Table: analytics.feed_impressions
 * 
 * DO NOT EDIT - This file is auto-generated from the log config.
 */
public class FeedImpressionLogger extends BaseStructuredLogger {

    private static final String TOPIC_NAME = "worksphere-feed-impressions";
    private static final String LOGGER_NAME = "FeedImpression";
    private static final String LOG_TYPE = "feed_impression";
    private static final String VERSION = "1.0.0";

    public FeedImpressionLogger() {
        super(TOPIC_NAME, LOGGER_NAME, LOG_TYPE, VERSION);
    }

    public FeedImpressionLogger(String kafkaBootstrapServers) {
        super(TOPIC_NAME, LOGGER_NAME, LOG_TYPE, VERSION, kafkaBootstrapServers);
    }

    /**
     * Log a FeedImpression event.
     */
    public void log(Instant timestamp, LocalDate eventDate, Long userId, Long postId, Long authorId, Integer position, Double score, String source, String targetType, Long targetId, Double featEngagement, Double featRecencyHours, Double featAffinity, Integer featReactionCount, Integer featCommentCount, Integer featAuthorFollowerCount, Boolean featIsRecommended, Boolean featHasAttachment, Boolean featHasPoll, Integer featSocialDistance) {
        LogRecord record = new LogRecord(timestamp, eventDate, userId, postId, authorId, position, score, source, targetType, targetId, featEngagement, featRecencyHours, featAffinity, featReactionCount, featCommentCount, featAuthorFollowerCount, featIsRecommended, featHasAttachment, featHasPoll, featSocialDistance);
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
    private final Long postId;
    private final Long authorId;
    private final Integer position;
    private final Double score;
    private final String source;
    private final String targetType;
    private final Long targetId;
    private final Double featEngagement;
    private final Double featRecencyHours;
    private final Double featAffinity;
    private final Integer featReactionCount;
    private final Integer featCommentCount;
    private final Integer featAuthorFollowerCount;
    private final Boolean featIsRecommended;
    private final Boolean featHasAttachment;
    private final Boolean featHasPoll;
    private final Integer featSocialDistance;

        public LogRecord(Instant timestamp, LocalDate eventDate, Long userId, Long postId, Long authorId, Integer position, Double score, String source, String targetType, Long targetId, Double featEngagement, Double featRecencyHours, Double featAffinity, Integer featReactionCount, Integer featCommentCount, Integer featAuthorFollowerCount, Boolean featIsRecommended, Boolean featHasAttachment, Boolean featHasPoll, Integer featSocialDistance) {
        this.timestamp = timestamp;
        this.eventDate = eventDate;
        this.userId = userId;
        this.postId = postId;
        this.authorId = authorId;
        this.position = position;
        this.score = score;
        this.source = source;
        this.targetType = targetType;
        this.targetId = targetId;
        this.featEngagement = featEngagement;
        this.featRecencyHours = featRecencyHours;
        this.featAffinity = featAffinity;
        this.featReactionCount = featReactionCount;
        this.featCommentCount = featCommentCount;
        this.featAuthorFollowerCount = featAuthorFollowerCount;
        this.featIsRecommended = featIsRecommended;
        this.featHasAttachment = featHasAttachment;
        this.featHasPoll = featHasPoll;
        this.featSocialDistance = featSocialDistance;
        }

        // Getters
        @JsonProperty("timestamp")
        public Instant getTimestamp() { return timestamp; }
        @JsonProperty("event_date")
        public LocalDate getEventDate() { return eventDate; }
        @JsonProperty("user_id")
        public Long getUserId() { return userId; }
        @JsonProperty("post_id")
        public Long getPostId() { return postId; }
        @JsonProperty("author_id")
        public Long getAuthorId() { return authorId; }
        @JsonProperty("position")
        public Integer getPosition() { return position; }
        @JsonProperty("score")
        public Double getScore() { return score; }
        @JsonProperty("source")
        public String getSource() { return source; }
        @JsonProperty("target_type")
        public String getTargetType() { return targetType; }
        @JsonProperty("target_id")
        public Long getTargetId() { return targetId; }
        @JsonProperty("feat_engagement")
        public Double getFeatEngagement() { return featEngagement; }
        @JsonProperty("feat_recency_hours")
        public Double getFeatRecencyHours() { return featRecencyHours; }
        @JsonProperty("feat_affinity")
        public Double getFeatAffinity() { return featAffinity; }
        @JsonProperty("feat_reaction_count")
        public Integer getFeatReactionCount() { return featReactionCount; }
        @JsonProperty("feat_comment_count")
        public Integer getFeatCommentCount() { return featCommentCount; }
        @JsonProperty("feat_author_follower_count")
        public Integer getFeatAuthorFollowerCount() { return featAuthorFollowerCount; }
        @JsonProperty("feat_is_recommended")
        public Boolean getFeatIsRecommended() { return featIsRecommended; }
        @JsonProperty("feat_has_attachment")
        public Boolean getFeatHasAttachment() { return featHasAttachment; }
        @JsonProperty("feat_has_poll")
        public Boolean getFeatHasPoll() { return featHasPoll; }
        @JsonProperty("feat_social_distance")
        public Integer getFeatSocialDistance() { return featSocialDistance; }
    }

    /**
     * Builder for creating log records.
     */
    public static class Builder {
        private Instant timestamp;
        private LocalDate eventDate;
        private Long userId;
        private Long postId;
        private Long authorId;
        private Integer position;
        private Double score;
        private String source;
        private String targetType;
        private Long targetId;
        private Double featEngagement;
        private Double featRecencyHours;
        private Double featAffinity;
        private Integer featReactionCount;
        private Integer featCommentCount;
        private Integer featAuthorFollowerCount;
        private Boolean featIsRecommended;
        private Boolean featHasAttachment;
        private Boolean featHasPoll;
        private Integer featSocialDistance;

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
        public Builder postId(Long postId) {
            this.postId = postId;
            return this;
        }
        public Builder authorId(Long authorId) {
            this.authorId = authorId;
            return this;
        }
        public Builder position(Integer position) {
            this.position = position;
            return this;
        }
        public Builder score(Double score) {
            this.score = score;
            return this;
        }
        public Builder source(String source) {
            this.source = source;
            return this;
        }
        public Builder targetType(String targetType) {
            this.targetType = targetType;
            return this;
        }
        public Builder targetId(Long targetId) {
            this.targetId = targetId;
            return this;
        }
        public Builder featEngagement(Double featEngagement) {
            this.featEngagement = featEngagement;
            return this;
        }
        public Builder featRecencyHours(Double featRecencyHours) {
            this.featRecencyHours = featRecencyHours;
            return this;
        }
        public Builder featAffinity(Double featAffinity) {
            this.featAffinity = featAffinity;
            return this;
        }
        public Builder featReactionCount(Integer featReactionCount) {
            this.featReactionCount = featReactionCount;
            return this;
        }
        public Builder featCommentCount(Integer featCommentCount) {
            this.featCommentCount = featCommentCount;
            return this;
        }
        public Builder featAuthorFollowerCount(Integer featAuthorFollowerCount) {
            this.featAuthorFollowerCount = featAuthorFollowerCount;
            return this;
        }
        public Builder featIsRecommended(Boolean featIsRecommended) {
            this.featIsRecommended = featIsRecommended;
            return this;
        }
        public Builder featHasAttachment(Boolean featHasAttachment) {
            this.featHasAttachment = featHasAttachment;
            return this;
        }
        public Builder featHasPoll(Boolean featHasPoll) {
            this.featHasPoll = featHasPoll;
            return this;
        }
        public Builder featSocialDistance(Integer featSocialDistance) {
            this.featSocialDistance = featSocialDistance;
            return this;
        }

        public LogRecord build() {
            return new LogRecord(timestamp, eventDate, userId, postId, authorId, position, score, source, targetType, targetId, featEngagement, featRecencyHours, featAffinity, featReactionCount, featCommentCount, featAuthorFollowerCount, featIsRecommended, featHasAttachment, featHasPoll, featSocialDistance);
        }
    }
}
