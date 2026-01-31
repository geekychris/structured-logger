"""
Generated structured logger for Logs user activity events such as login, logout, and API access.

Version: 1.0.0
Kafka Topic: user-events
Warehouse Table: analytics_logs.user_activity_log

DO NOT EDIT - This file is auto-generated from the log config.
"""

from datetime import datetime, date
from typing import Optional, Dict, List, Any
from structured_logging.base_logger import BaseStructuredLogger


class UserActivityLogLogger(BaseStructuredLogger):
    """Structured logger for UserActivityLog events."""

    def __init__(self, kafka_bootstrap_servers: Optional[str] = None):
        """Initialize the UserActivityLog logger."""
        super().__init__(
            topic_name="user-events",
            logger_name="UserActivityLog",
            kafka_bootstrap_servers=kafka_bootstrap_servers,
        )

    def log(
        self,
        user_id: str, username: Optional[str] = None, event_type: str, event_date: date, timestamp: datetime, ip_address: Optional[str] = None, user_agent: Optional[str] = None, endpoint: Optional[str] = None, http_method: Optional[str] = None, status_code: Optional[int] = None, response_time_ms: Optional[int] = None, session_id: Optional[str] = None, metadata: Optional[Dict[str, str]] = None,
    ) -> None:
        """
        Log a UserActivityLog event.

        Args:
            user_id: Unique identifier for the user
            username: Username of the user
            event_type: Type of user activity event (LOGIN, LOGOUT, API_ACCESS, etc.)
            event_date: Date of the event (YYYY-MM-DD) for partitioning
            timestamp: Precise timestamp of the event in ISO-8601 format
            ip_address: IP address of the user
            user_agent: Browser user agent string
            endpoint: API endpoint accessed (for API_ACCESS events)
            http_method: HTTP method used (GET, POST, PUT, DELETE, etc.)
            status_code: HTTP status code returned
            response_time_ms: Response time in milliseconds
            session_id: Session identifier
            metadata: Additional metadata as key-value pairs
        """
        record = {
            "user_id": user_id,
            "username": username,
            "event_type": event_type,
            "event_date": event_date,
            "timestamp": timestamp,
            "ip_address": ip_address,
            "user_agent": user_agent,
            "endpoint": endpoint,
            "http_method": http_method,
            "status_code": status_code,
            "response_time_ms": response_time_ms,
            "session_id": session_id,
            "metadata": metadata,
        }

        # Remove None values for optional fields
        record = {k: v for k, v in record.items() if v is not None}

        self.publish(key=user_id, log_record=record)

    @classmethod
    def builder(cls) -> "UserActivityLogLoggerBuilder":
        """Create a builder for constructing log records."""
        return UserActivityLogLoggerBuilder()


class UserActivityLogLoggerBuilder:
    """Builder for UserActivityLog log records."""

    def __init__(self):
        """Initialize the builder."""
        self._user_id: Optional[str] = None
        self._username: Optional[str] = None
        self._event_type: Optional[str] = None
        self._event_date: Optional[date] = None
        self._timestamp: Optional[datetime] = None
        self._ip_address: Optional[str] = None
        self._user_agent: Optional[str] = None
        self._endpoint: Optional[str] = None
        self._http_method: Optional[str] = None
        self._status_code: Optional[int] = None
        self._response_time_ms: Optional[int] = None
        self._session_id: Optional[str] = None
        self._metadata: Optional[Dict[str, str]] = None

    def user_id(self, value: str) -> "UserActivityLogLoggerBuilder":
        """Set user_id."""
        self._user_id = value
        return self
    def username(self, value: str) -> "UserActivityLogLoggerBuilder":
        """Set username."""
        self._username = value
        return self
    def event_type(self, value: str) -> "UserActivityLogLoggerBuilder":
        """Set event_type."""
        self._event_type = value
        return self
    def event_date(self, value: date) -> "UserActivityLogLoggerBuilder":
        """Set event_date."""
        self._event_date = value
        return self
    def timestamp(self, value: datetime) -> "UserActivityLogLoggerBuilder":
        """Set timestamp."""
        self._timestamp = value
        return self
    def ip_address(self, value: str) -> "UserActivityLogLoggerBuilder":
        """Set ip_address."""
        self._ip_address = value
        return self
    def user_agent(self, value: str) -> "UserActivityLogLoggerBuilder":
        """Set user_agent."""
        self._user_agent = value
        return self
    def endpoint(self, value: str) -> "UserActivityLogLoggerBuilder":
        """Set endpoint."""
        self._endpoint = value
        return self
    def http_method(self, value: str) -> "UserActivityLogLoggerBuilder":
        """Set http_method."""
        self._http_method = value
        return self
    def status_code(self, value: int) -> "UserActivityLogLoggerBuilder":
        """Set status_code."""
        self._status_code = value
        return self
    def response_time_ms(self, value: int) -> "UserActivityLogLoggerBuilder":
        """Set response_time_ms."""
        self._response_time_ms = value
        return self
    def session_id(self, value: str) -> "UserActivityLogLoggerBuilder":
        """Set session_id."""
        self._session_id = value
        return self
    def metadata(self, value: Dict[str, str]) -> "UserActivityLogLoggerBuilder":
        """Set metadata."""
        self._metadata = value
        return self

    def build(self) -> Dict[str, Any]:
        """Build and return the log record dictionary."""
        record = {
            "user_id": self._user_id,
            "username": self._username,
            "event_type": self._event_type,
            "event_date": self._event_date,
            "timestamp": self._timestamp,
            "ip_address": self._ip_address,
            "user_agent": self._user_agent,
            "endpoint": self._endpoint,
            "http_method": self._http_method,
            "status_code": self._status_code,
            "response_time_ms": self._response_time_ms,
            "session_id": self._session_id,
            "metadata": self._metadata,
        }
        return {k: v for k, v in record.items() if v is not None}
