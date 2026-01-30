"""
Generated structured logger for Tracks user interaction events in the application.

Version: 1.0.0
Kafka Topic: user-events
Warehouse Table: analytics.logs.user_events

DO NOT EDIT - This file is auto-generated from the log config.
"""

from datetime import datetime, date
from typing import Optional, Dict, List, Any
from structured_logging.base_logger import BaseStructuredLogger


class UserEventsLogger(BaseStructuredLogger):
    """Structured logger for UserEvents events."""

    def __init__(self, kafka_bootstrap_servers: Optional[str] = None):
        """Initialize the UserEvents logger."""
        super().__init__(
            topic_name="user-events",
            logger_name="UserEvents",
            kafka_bootstrap_servers=kafka_bootstrap_servers,
        )

    def log(
        self,
        timestamp: datetime, event_date: date, user_id: str, session_id: str, event_type: str, page_url: Optional[str] = None, properties: Optional[Dict[str, str]] = None, device_type: Optional[str] = None, duration_ms: Optional[int] = None,
    ) -> None:
        """
        Log a UserEvents event.

        Args:
            timestamp: Event timestamp
            event_date: Event date for partitioning
            user_id: Unique user identifier
            session_id: Session identifier
            event_type: Type of event (click, view, purchase, etc.)
            page_url: URL of the page where event occurred
            properties: Additional event properties
            device_type: Device type (mobile, desktop, tablet)
            duration_ms: Duration of the event in milliseconds
        """
        record = {
            "timestamp": timestamp,
            "event_date": event_date,
            "user_id": user_id,
            "session_id": session_id,
            "event_type": event_type,
            "page_url": page_url,
            "properties": properties,
            "device_type": device_type,
            "duration_ms": duration_ms,
        }

        # Remove None values for optional fields
        record = {k: v for k, v in record.items() if v is not None}

        self.publish(key=user_id, log_record=record)

    @classmethod
    def builder(cls) -> "UserEventsLoggerBuilder":
        """Create a builder for constructing log records."""
        return UserEventsLoggerBuilder()


class UserEventsLoggerBuilder:
    """Builder for UserEvents log records."""

    def __init__(self):
        """Initialize the builder."""
        self._timestamp: Optional[datetime] = None
        self._event_date: Optional[date] = None
        self._user_id: Optional[str] = None
        self._session_id: Optional[str] = None
        self._event_type: Optional[str] = None
        self._page_url: Optional[str] = None
        self._properties: Optional[Dict[str, str]] = None
        self._device_type: Optional[str] = None
        self._duration_ms: Optional[int] = None

    def timestamp(self, value: datetime) -> "UserEventsLoggerBuilder":
        """Set timestamp."""
        self._timestamp = value
        return self
    def event_date(self, value: date) -> "UserEventsLoggerBuilder":
        """Set event_date."""
        self._event_date = value
        return self
    def user_id(self, value: str) -> "UserEventsLoggerBuilder":
        """Set user_id."""
        self._user_id = value
        return self
    def session_id(self, value: str) -> "UserEventsLoggerBuilder":
        """Set session_id."""
        self._session_id = value
        return self
    def event_type(self, value: str) -> "UserEventsLoggerBuilder":
        """Set event_type."""
        self._event_type = value
        return self
    def page_url(self, value: str) -> "UserEventsLoggerBuilder":
        """Set page_url."""
        self._page_url = value
        return self
    def properties(self, value: Dict[str, str]) -> "UserEventsLoggerBuilder":
        """Set properties."""
        self._properties = value
        return self
    def device_type(self, value: str) -> "UserEventsLoggerBuilder":
        """Set device_type."""
        self._device_type = value
        return self
    def duration_ms(self, value: int) -> "UserEventsLoggerBuilder":
        """Set duration_ms."""
        self._duration_ms = value
        return self

    def build(self) -> Dict[str, Any]:
        """Build and return the log record dictionary."""
        record = {
            "timestamp": self._timestamp,
            "event_date": self._event_date,
            "user_id": self._user_id,
            "session_id": self._session_id,
            "event_type": self._event_type,
            "page_url": self._page_url,
            "properties": self._properties,
            "device_type": self._device_type,
            "duration_ms": self._duration_ms,
        }
        return {k: v for k, v in record.items() if v is not None}
