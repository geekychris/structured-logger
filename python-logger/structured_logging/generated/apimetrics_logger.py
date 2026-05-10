"""
Generated structured logger for API endpoint performance and usage metrics.

Version: 1.0.0
Kafka Topic: api-metrics
Warehouse Table: analytics_logs.api_metrics

DO NOT EDIT - This file is auto-generated from the log config.
"""

from datetime import datetime, date
from typing import Optional, Dict, List, Any
from structured_logging.base_logger import BaseStructuredLogger


# Field definitions and transport config baked in at generation time.
# Used by BaseStructuredLogger to derive Avro schemas (for the kafka-avro
# and s3-avro/parquet sinks) and to build the sink chain.
_FIELDS = [   {'name': 'timestamp', 'type': 'timestamp', 'required': True, 'description': 'Metric timestamp'},
    {'name': 'metric_date', 'type': 'date', 'required': True, 'description': 'Date for partitioning'},
    {'name': 'service_name', 'type': 'string', 'required': True, 'description': 'Name of the service'},
    {'name': 'endpoint', 'type': 'string', 'required': True, 'description': 'API endpoint path'},
    {'name': 'method', 'type': 'string', 'required': True, 'description': 'HTTP method (GET, POST, etc.)'},
    {'name': 'status_code', 'type': 'int', 'required': True, 'description': 'HTTP status code'},
    {'name': 'response_time_ms', 'type': 'long', 'required': True, 'description': 'Response time in milliseconds'},
    {'name': 'request_size_bytes', 'type': 'long', 'required': False, 'description': 'Request payload size'},
    {'name': 'response_size_bytes', 'type': 'long', 'required': False, 'description': 'Response payload size'},
    {'name': 'user_id', 'type': 'string', 'required': False, 'description': 'User identifier if authenticated'},
    {'name': 'client_ip', 'type': 'string', 'required': False, 'description': 'Client IP address'},
    {'name': 'error_message', 'type': 'string', 'required': False, 'description': 'Error message if request failed'}]
_TRANSPORT = None


class ApiMetricsLogger(BaseStructuredLogger):
    """Structured logger for ApiMetrics events."""

    def __init__(self, kafka_bootstrap_servers: Optional[str] = None,
                 transport: Optional[Dict[str, Any]] = None,
                 sink=None):
        """Initialize the ApiMetrics logger.

        transport overrides the config-baked default; sink overrides everything
        (useful for tests). STRUCTURED_LOG_SINKS env var overrides transport.sinks.
        """
        super().__init__(
            topic_name="api-metrics",
            logger_name="ApiMetrics",
            kafka_bootstrap_servers=kafka_bootstrap_servers,
            log_type="api_metrics",
            log_class="ApiMetrics",
            version="1.0.0",
            fields=_FIELDS,
            transport=transport if transport is not None else _TRANSPORT,
            sink=sink,
        )

    def log(
        self,
        timestamp: datetime, metric_date: date, service_name: str, endpoint: str, method: str, status_code: int, response_time_ms: int, request_size_bytes: Optional[int] = None, response_size_bytes: Optional[int] = None, user_id: Optional[str] = None, client_ip: Optional[str] = None, error_message: Optional[str] = None,
    ) -> None:
        """
        Log a ApiMetrics event.

        Args:
            timestamp: Metric timestamp
            metric_date: Date for partitioning
            service_name: Name of the service
            endpoint: API endpoint path
            method: HTTP method (GET, POST, etc.)
            status_code: HTTP status code
            response_time_ms: Response time in milliseconds
            request_size_bytes: Request payload size
            response_size_bytes: Response payload size
            user_id: User identifier if authenticated
            client_ip: Client IP address
            error_message: Error message if request failed
        """
        record = {
            "timestamp": timestamp,
            "metric_date": metric_date,
            "service_name": service_name,
            "endpoint": endpoint,
            "method": method,
            "status_code": status_code,
            "response_time_ms": response_time_ms,
            "request_size_bytes": request_size_bytes,
            "response_size_bytes": response_size_bytes,
            "user_id": user_id,
            "client_ip": client_ip,
            "error_message": error_message,
        }

        # Remove None values for optional fields
        record = {k: v for k, v in record.items() if v is not None}

        self.publish(key=user_id, log_record=record)

    @classmethod
    def builder(cls) -> "ApiMetricsLoggerBuilder":
        """Create a builder for constructing log records."""
        return ApiMetricsLoggerBuilder()


class ApiMetricsLoggerBuilder:
    """Builder for ApiMetrics log records."""

    def __init__(self):
        """Initialize the builder."""
        self._timestamp: Optional[datetime] = None
        self._metric_date: Optional[date] = None
        self._service_name: Optional[str] = None
        self._endpoint: Optional[str] = None
        self._method: Optional[str] = None
        self._status_code: Optional[int] = None
        self._response_time_ms: Optional[int] = None
        self._request_size_bytes: Optional[int] = None
        self._response_size_bytes: Optional[int] = None
        self._user_id: Optional[str] = None
        self._client_ip: Optional[str] = None
        self._error_message: Optional[str] = None

    def timestamp(self, value: datetime) -> "ApiMetricsLoggerBuilder":
        """Set timestamp."""
        self._timestamp = value
        return self
    def metric_date(self, value: date) -> "ApiMetricsLoggerBuilder":
        """Set metric_date."""
        self._metric_date = value
        return self
    def service_name(self, value: str) -> "ApiMetricsLoggerBuilder":
        """Set service_name."""
        self._service_name = value
        return self
    def endpoint(self, value: str) -> "ApiMetricsLoggerBuilder":
        """Set endpoint."""
        self._endpoint = value
        return self
    def method(self, value: str) -> "ApiMetricsLoggerBuilder":
        """Set method."""
        self._method = value
        return self
    def status_code(self, value: int) -> "ApiMetricsLoggerBuilder":
        """Set status_code."""
        self._status_code = value
        return self
    def response_time_ms(self, value: int) -> "ApiMetricsLoggerBuilder":
        """Set response_time_ms."""
        self._response_time_ms = value
        return self
    def request_size_bytes(self, value: int) -> "ApiMetricsLoggerBuilder":
        """Set request_size_bytes."""
        self._request_size_bytes = value
        return self
    def response_size_bytes(self, value: int) -> "ApiMetricsLoggerBuilder":
        """Set response_size_bytes."""
        self._response_size_bytes = value
        return self
    def user_id(self, value: str) -> "ApiMetricsLoggerBuilder":
        """Set user_id."""
        self._user_id = value
        return self
    def client_ip(self, value: str) -> "ApiMetricsLoggerBuilder":
        """Set client_ip."""
        self._client_ip = value
        return self
    def error_message(self, value: str) -> "ApiMetricsLoggerBuilder":
        """Set error_message."""
        self._error_message = value
        return self

    def build(self) -> Dict[str, Any]:
        """Build and return the log record dictionary."""
        record = {
            "timestamp": self._timestamp,
            "metric_date": self._metric_date,
            "service_name": self._service_name,
            "endpoint": self._endpoint,
            "method": self._method,
            "status_code": self._status_code,
            "response_time_ms": self._response_time_ms,
            "request_size_bytes": self._request_size_bytes,
            "response_size_bytes": self._response_size_bytes,
            "user_id": self._user_id,
            "client_ip": self._client_ip,
            "error_message": self._error_message,
        }
        return {k: v for k, v in record.items() if v is not None}
