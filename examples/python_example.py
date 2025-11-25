#!/usr/bin/env python3
"""Example usage of generated structured loggers in Python."""

from datetime import datetime, date
import sys
import os

# Add the python-logger to path for imports
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'python-logger'))

from structured_logging.generated.userevents_logger import UserEventsLogger
from structured_logging.generated.apimetrics_logger import ApiMetricsLogger


def log_user_events():
    """Example of logging user events."""
    with UserEventsLogger() as logger:
        
        # Example 1: Log a page view event
        logger.log(
            timestamp=datetime.utcnow(),
            event_date=date.today(),
            user_id="user_12345",
            session_id="session_abc123",
            event_type="page_view",
            page_url="/products/laptop",
            properties={
                "category": "electronics",
                "source": "search"
            },
            device_type="desktop",
            duration_ms=2500
        )

        # Example 2: Log a click event with minimal fields
        logger.log(
            timestamp=datetime.utcnow(),
            event_date=date.today(),
            user_id="user_67890",
            session_id="session_xyz789",
            event_type="click",
            page_url="/checkout",
            properties={
                "button_id": "checkout",
                "cart_value": "299.99"
            },
            device_type="mobile",
            duration_ms=1200
        )

        # Example 3: Log a purchase event
        logger.log(
            timestamp=datetime.utcnow(),
            event_date=date.today(),
            user_id="user_54321",
            session_id="session_def456",
            event_type="purchase",
            page_url="/confirmation",
            properties={
                "order_id": "ORD-12345",
                "amount": "1299.99",
                "payment_method": "credit_card"
            },
            device_type="desktop"
        )

        print("User events logged successfully")


def log_api_metrics():
    """Example of logging API metrics."""
    with ApiMetricsLogger() as logger:
        
        # Example 1: Log successful API call
        logger.log(
            timestamp=datetime.utcnow(),
            metric_date=date.today(),
            service_name="user-service",
            endpoint="/api/v1/users",
            method="GET",
            status_code=200,
            response_time_ms=45,
            request_size_bytes=None,
            response_size_bytes=1024,
            user_id="user_12345",
            client_ip="192.168.1.100",
            error_message=None
        )

        # Example 2: Log failed API call
        logger.log(
            timestamp=datetime.utcnow(),
            metric_date=date.today(),
            service_name="payment-service",
            endpoint="/api/v1/payments",
            method="POST",
            status_code=500,
            response_time_ms=1523,
            request_size_bytes=2048,
            response_size_bytes=0,
            user_id="user_67890",
            client_ip="10.0.0.5",
            error_message="Database connection timeout"
        )

        # Example 3: Log slow API call
        logger.log(
            timestamp=datetime.utcnow(),
            metric_date=date.today(),
            service_name="search-service",
            endpoint="/api/v1/search",
            method="GET",
            status_code=200,
            response_time_ms=3452,
            request_size_bytes=512,
            response_size_bytes=15360,
            user_id="user_99999",
            client_ip="203.0.113.42"
        )

        print("API metrics logged successfully")


def main():
    """Main function to run examples."""
    print("Running structured logging examples...")
    print()
    
    try:
        log_user_events()
        print()
        log_api_metrics()
        print()
        print("All examples completed successfully!")
    except Exception as e:
        print(f"Error running examples: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1
    
    return 0


if __name__ == "__main__":
    sys.exit(main())
