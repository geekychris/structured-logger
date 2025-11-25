#!/usr/bin/env python3
"""
Test Data Generator for Structured Logging System

Generates realistic test data for user_events and api_metrics topics.
Useful for testing, load testing, and demonstrations.

Usage:
    python3 generate_test_data.py                    # Generate 100 events
    python3 generate_test_data.py --count 1000       # Generate 1000 events
    python3 generate_test_data.py --rate 10          # 10 events per second
    python3 generate_test_data.py --continuous       # Continuous generation
"""

import sys
import os
import json
import random
import time
import argparse
from datetime import datetime, date
from kafka import KafkaProducer

# Add parent directory to path for imports
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'python-logger'))

# Sample data for realistic generation
USERS = [f"user_{i:04d}" for i in range(1, 101)]  # user_0001 to user_0100
SESSIONS = [f"sess_{i:06d}" for i in range(1, 501)]  # 500 sessions

EVENT_TYPES = ["click", "page_view", "purchase", "search", "login", "logout"]
PAGE_URLS = [
    "/home",
    "/products",
    "/products/widget-1",
    "/products/widget-2",
    "/checkout",
    "/cart",
    "/profile",
    "/search",
    "/about",
    "/contact"
]
DEVICE_TYPES = ["desktop", "mobile", "tablet"]

SERVICES = ["user-service", "payment-service", "search-service", "auth-service", "product-service"]
ENDPOINTS = [
    "/api/users",
    "/api/products",
    "/api/checkout",
    "/api/search",
    "/api/login",
    "/api/payment/process",
    "/api/cart/add",
    "/api/cart/remove",
]
HTTP_METHODS = ["GET", "POST", "PUT", "DELETE"]


def generate_user_event():
    """Generate a realistic user event."""
    event_type = random.choice(EVENT_TYPES)
    
    # Events have different characteristics
    if event_type == "purchase":
        # Purchases are longer duration
        duration = random.randint(5000, 30000)
    elif event_type == "page_view":
        duration = random.randint(500, 5000)
    else:
        duration = random.randint(100, 2000)
    
    return {
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "event_date": date.today().isoformat(),
        "user_id": random.choice(USERS),
        "session_id": random.choice(SESSIONS),
        "event_type": event_type,
        "page_url": random.choice(PAGE_URLS),
        "properties": {
            "browser": random.choice(["Chrome", "Safari", "Firefox", "Edge"]),
            "os": random.choice(["MacOS", "Windows", "Linux", "iOS", "Android"]),
            "referrer": random.choice(["google.com", "direct", "facebook.com", "twitter.com"])
        },
        "device_type": random.choice(DEVICE_TYPES),
        "duration_ms": duration
    }


def generate_api_metric():
    """Generate a realistic API metric."""
    endpoint = random.choice(ENDPOINTS)
    method = random.choice(HTTP_METHODS)
    service = random.choice(SERVICES)
    
    # Most requests are successful
    if random.random() < 0.95:
        status_code = random.choice([200, 200, 200, 201, 204])
        response_time = random.randint(10, 500)
    else:
        # Some errors
        status_code = random.choice([400, 404, 500, 503])
        response_time = random.randint(100, 5000)
    
    return {
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "metric_date": date.today().isoformat(),
        "service_name": service,
        "endpoint": endpoint,
        "method": method,
        "status_code": status_code,
        "response_time": response_time,
        "request_size": random.randint(100, 10000),
        "response_size": random.randint(500, 50000)
    }


def create_producer(bootstrap_servers='localhost:9092'):
    """Create Kafka producer."""
    return KafkaProducer(
        bootstrap_servers=bootstrap_servers,
        value_serializer=lambda v: json.dumps(v).encode('utf-8'),
        compression_type=None
    )


def send_event(producer, topic, event):
    """Send an event to Kafka."""
    future = producer.send(topic, event)
    return future


def generate_batch(producer, count=100, show_progress=True):
    """Generate a batch of test data."""
    print(f"Generating {count} events...")
    
    user_events = 0
    api_metrics = 0
    
    for i in range(count):
        # Mix of user events and API metrics (60/40 split)
        if random.random() < 0.6:
            event = generate_user_event()
            send_event(producer, 'user-events', event)
            user_events += 1
        else:
            metric = generate_api_metric()
            send_event(producer, 'api-metrics', metric)
            api_metrics += 1
        
        if show_progress and (i + 1) % 100 == 0:
            print(f"  Generated {i + 1}/{count} events...")
    
    producer.flush()
    
    print(f"\n✓ Generated {count} total events:")
    print(f"  - {user_events} user events → user-events topic")
    print(f"  - {api_metrics} API metrics → api-metrics topic")


def generate_continuous(producer, rate=10):
    """Generate data continuously at specified rate (events per second)."""
    print(f"Generating events continuously at {rate} events/second...")
    print("Press Ctrl+C to stop\n")
    
    interval = 1.0 / rate
    count = 0
    
    try:
        while True:
            # Generate one event
            if random.random() < 0.6:
                event = generate_user_event()
                send_event(producer, 'user-events', event)
            else:
                metric = generate_api_metric()
                send_event(producer, 'api-metrics', metric)
            
            count += 1
            
            if count % 100 == 0:
                print(f"Generated {count} events...")
            
            time.sleep(interval)
            
    except KeyboardInterrupt:
        print(f"\n\n✓ Stopped. Generated {count} total events.")
        producer.flush()


def generate_timed(producer, duration_seconds=60, rate=10):
    """Generate data for a specific duration."""
    print(f"Generating events for {duration_seconds} seconds at {rate} events/second...")
    
    interval = 1.0 / rate
    count = 0
    start_time = time.time()
    
    while time.time() - start_time < duration_seconds:
        # Generate one event
        if random.random() < 0.6:
            event = generate_user_event()
            send_event(producer, 'user-events', event)
        else:
            metric = generate_api_metric()
            send_event(producer, 'api-metrics', metric)
        
        count += 1
        
        if count % 100 == 0:
            elapsed = int(time.time() - start_time)
            remaining = duration_seconds - elapsed
            print(f"Generated {count} events... ({elapsed}s elapsed, {remaining}s remaining)")
        
        time.sleep(interval)
    
    producer.flush()
    
    print(f"\n✓ Generated {count} total events in {duration_seconds} seconds")
    print(f"  Average rate: {count / duration_seconds:.1f} events/second")


def main():
    parser = argparse.ArgumentParser(
        description="Generate test data for structured logging system",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s                           # Generate 100 events (default)
  %(prog)s --count 1000              # Generate 1000 events
  %(prog)s --rate 10                 # 10 events per second continuously
  %(prog)s --continuous              # Continuous generation (1 event/sec)
  %(prog)s --duration 300 --rate 5   # Generate for 5 minutes at 5/sec
  %(prog)s --kafka kafka:29092       # Use different Kafka server
        """
    )
    
    parser.add_argument(
        '--count',
        type=int,
        default=100,
        help='Number of events to generate (default: 100)'
    )
    
    parser.add_argument(
        '--continuous',
        action='store_true',
        help='Generate events continuously (press Ctrl+C to stop)'
    )
    
    parser.add_argument(
        '--duration',
        type=int,
        help='Generate events for this many seconds'
    )
    
    parser.add_argument(
        '--rate',
        type=float,
        default=1.0,
        help='Events per second for continuous/duration mode (default: 1.0)'
    )
    
    parser.add_argument(
        '--kafka',
        default='localhost:9092',
        help='Kafka bootstrap servers (default: localhost:9092)'
    )
    
    args = parser.parse_args()
    
    # Create producer
    try:
        producer = create_producer(args.kafka)
    except Exception as e:
        print(f"Error connecting to Kafka at {args.kafka}: {e}")
        print("\nMake sure Kafka is running:")
        print("  docker-compose up -d kafka")
        sys.exit(1)
    
    try:
        if args.continuous:
            # Continuous generation
            generate_continuous(producer, args.rate)
        elif args.duration:
            # Timed generation
            generate_timed(producer, args.duration, args.rate)
        else:
            # Batch generation
            generate_batch(producer, args.count)
    finally:
        producer.close()


if __name__ == '__main__':
    main()
