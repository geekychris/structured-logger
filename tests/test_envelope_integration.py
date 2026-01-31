#!/usr/bin/env python3
"""
Integration test for envelope format.
Tests that messages flow correctly with envelope from producer to Kafka to consumer.
"""

import json
import subprocess
import time
from pathlib import Path


def run_command(cmd, check=True):
    """Run a shell command and return output."""
    result = subprocess.run(
        cmd, shell=True, capture_output=True, text=True, check=check
    )
    return result.stdout, result.stderr, result.returncode


def test_kafka_message_has_envelope():
    """Test that messages in Kafka have the envelope wrapper."""
    print("=" * 60)
    print("Test: Kafka messages have envelope wrapper")
    print("=" * 60)
    
    # Send a test message
    print("\n1. Running Java demo to send messages...")
    stdout, stderr, code = run_command("./run-java-demo.sh 2>&1 | grep 'logged successfully'", check=False)
    if code != 0 or "logged successfully" not in stdout:
        print(f"  ❌ Failed to run demo: {stderr}")
        return False
    print("  ✓ Demo ran successfully")
    
    # Wait for Kafka to receive
    time.sleep(2)
    
    # Fetch latest message from Kafka
    print("\n2. Fetching latest message from Kafka...")
    cmd = """docker exec kafka kafka-console-consumer \
        --bootstrap-server localhost:9092 \
        --topic user-events \
        --max-messages 1 \
        --timeout-ms 3000 2>&1 | grep -v 'Processed'"""
    
    stdout, stderr, code = run_command(cmd, check=False)
    
    if not stdout.strip():
        print("  ❌ No message received from Kafka")
        return False
    
    # Parse message
    try:
        message = json.loads(stdout.strip().split('\n')[0])
    except json.JSONDecodeError as e:
        print(f"  ❌ Failed to parse JSON: {e}")
        print(f"     Raw output: {stdout[:200]}")
        return False
    
    # Verify envelope structure
    print("\n3. Verifying envelope structure...")
    required_fields = ["_log_type", "_log_class", "_version", "data"]
    
    for field in required_fields:
        if field not in message:
            print(f"  ❌ Missing envelope field: {field}")
            print(f"     Message keys: {list(message.keys())}")
            return False
    
    print(f"  ✓ Has _log_type: {message['_log_type']}")
    print(f"  ✓ Has _log_class: {message['_log_class']}")
    print(f"  ✓ Has _version: {message['_version']}")
    print(f"  ✓ Has data field with keys: {list(message['data'].keys())[:3]}...")
    
    # Verify data field has business data
    if not isinstance(message["data"], dict):
        print(f"  ❌ Data field is not a dict: {type(message['data'])}")
        return False
    
    if "user_id" not in message["data"]:
        print(f"  ❌ Data field missing business fields")
        return False
    
    print(f"  ✓ Data contains business fields (user_id: {message['data']['user_id']})")
    
    return True


def test_consumer_processes_envelope():
    """Test that Spark consumer correctly processes envelope format."""
    print("\n" + "=" * 60)
    print("Test: Consumer processes envelope and writes to Iceberg")
    print("=" * 60)
    
    # Get current row count
    print("\n1. Getting current row count...")
    cmd = """docker exec trino trino --execute "SELECT COUNT(*) FROM iceberg.analytics_logs.user_events" 2>/dev/null | tail -1"""
    stdout, stderr, code = run_command(cmd, check=False)
    
    if code != 0:
        print(f"  ❌ Failed to query Trino: {stderr}")
        return False
    
    try:
        count_before = int(stdout.strip().strip('"'))
        print(f"  ✓ Current count: {count_before}")
    except ValueError:
        print(f"  ❌ Invalid count: {stdout}")
        return False
    
    # Send new messages
    print("\n2. Sending new messages...")
    stdout, stderr, code = run_command("./run-java-demo.sh 2>&1 | grep 'logged successfully'", check=False)
    if code != 0:
        print(f"  ❌ Failed to send messages: {stderr}")
        return False
    print("  ✓ Messages sent")
    
    # Wait for Spark to process
    print("\n3. Waiting for Spark consumer to process (15 seconds)...")
    time.sleep(15)
    
    # Check new row count
    print("\n4. Checking new row count...")
    stdout, stderr, code = run_command(cmd, check=False)
    
    if code != 0:
        print(f"  ❌ Failed to query Trino: {stderr}")
        return False
    
    try:
        count_after = int(stdout.strip().strip('"'))
        print(f"  ✓ New count: {count_after}")
    except ValueError:
        print(f"  ❌ Invalid count: {stdout}")
        return False
    
    # Verify rows were added
    if count_after <= count_before:
        print(f"  ❌ No new rows added (before: {count_before}, after: {count_after})")
        return False
    
    rows_added = count_after - count_before
    print(f"  ✓ Successfully added {rows_added} rows")
    
    if rows_added != 5:
        print(f"  ⚠️  Warning: Expected 5 rows, got {rows_added}")
    
    return True


def test_consumer_logs_for_errors():
    """Check consumer logs for any errors."""
    print("\n" + "=" * 60)
    print("Test: Consumer logs for errors")
    print("=" * 60)
    
    cmd = """docker exec spark-master tail -100 /opt/spark-data/consumer.log | grep -i "error\\|exception\\|failed" || echo "No errors found" """
    
    stdout, stderr, code = run_command(cmd, check=False)
    
    if "No errors found" in stdout:
        print("  ✓ No errors in consumer logs")
        return True
    else:
        print(f"  ⚠️  Found potential errors in logs:")
        print(stdout[:500])
        return False


def main():
    """Run all integration tests."""
    print("\n" + "=" * 60)
    print("ENVELOPE FORMAT INTEGRATION TESTS")
    print("=" * 60)
    
    # Change to project directory
    project_dir = Path(__file__).parent.parent
    import os
    os.chdir(project_dir)
    
    results = {}
    
    # Run tests
    results["kafka_envelope"] = test_kafka_message_has_envelope()
    results["consumer_processing"] = test_consumer_processes_envelope()
    results["consumer_logs"] = test_consumer_logs_for_errors()
    
    # Summary
    print("\n" + "=" * 60)
    print("TEST SUMMARY")
    print("=" * 60)
    
    for test_name, passed in results.items():
        status = "✅ PASS" if passed else "❌ FAIL"
        print(f"{status}: {test_name}")
    
    all_passed = all(results.values())
    
    if all_passed:
        print("\n✅ All tests passed!")
        return 0
    else:
        print("\n❌ Some tests failed")
        return 1


if __name__ == "__main__":
    exit(main())
