#!/bin/bash

echo "=========================================="
echo "Query Structured Logs with Trino"
echo "=========================================="
echo ""

# Helper function to run queries
run_query() {
    docker exec trino trino --execute "$1" 2>&1 | grep -v WARNING | grep -v "org.jline"
}

# Show available tables
echo "Available Tables:"
echo "----------------------------------------"
run_query "SHOW TABLES IN iceberg.analytics_logs;"
echo ""

# Count records in each table
echo "Record Counts:"
echo "----------------------------------------"
USER_EVENTS=$(run_query "SELECT COUNT(*) FROM iceberg.analytics_logs.user_events;" | tr -d '"')
API_METRICS=$(run_query "SELECT COUNT(*) FROM iceberg.analytics_logs.api_metrics;" | tr -d '"')
echo "user_events: $USER_EVENTS"
echo "api_metrics: $API_METRICS"
echo ""

# Recent user events
if [ "$USER_EVENTS" -gt 0 ]; then
    echo "Recent User Events (last 5):"
    echo "----------------------------------------"
    run_query "SELECT user_id, event_type, page_url, device_type, timestamp FROM iceberg.analytics_logs.user_events ORDER BY timestamp DESC LIMIT 5;"
    echo ""
fi

# Recent API metrics
if [ "$API_METRICS" -gt 0 ]; then
    echo "Recent API Metrics (last 5):"
    echo "----------------------------------------"
    run_query "SELECT service_name, endpoint, method, status_code, response_time_ms, timestamp FROM iceberg.analytics_logs.api_metrics ORDER BY timestamp DESC LIMIT 5;"
    echo ""
fi

# Summary stats
echo "=========================================="
echo "Summary Statistics"
echo "=========================================="
echo ""

if [ "$USER_EVENTS" -gt 0 ]; then
    echo "User Events by Type:"
    run_query "SELECT event_type, COUNT(*) as count FROM iceberg.analytics_logs.user_events GROUP BY event_type ORDER BY count DESC;"
    echo ""
    
    echo "User Events by Device:"
    run_query "SELECT device_type, COUNT(*) as count FROM iceberg.analytics_logs.user_events GROUP BY device_type ORDER BY count DESC;"
    echo ""
fi

if [ "$API_METRICS" -gt 0 ]; then
    echo "API Calls by Service:"
    run_query "SELECT service_name, COUNT(*) as calls, AVG(response_time_ms) as avg_response_ms FROM iceberg.analytics_logs.api_metrics GROUP BY service_name ORDER BY calls DESC;"
    echo ""
    
    echo "API Calls by Status Code:"
    run_query "SELECT status_code, COUNT(*) as count FROM iceberg.analytics_logs.api_metrics GROUP BY status_code ORDER BY status_code;"
    echo ""
fi

echo "=========================================="
echo "Custom Queries"
echo "=========================================="
echo ""
echo "To run custom queries, use:"
echo "  docker exec trino trino"
echo ""
echo "Example queries:"
echo "  USE iceberg.analytics_logs;"
echo "  SELECT * FROM user_events WHERE event_date = CURRENT_DATE;"
echo "  SELECT * FROM api_metrics WHERE status_code >= 400;"
echo ""
