#!/bin/bash

# Query Trino tables
# Usage: 
#   ./query-trino.sh                    # Interactive mode
#   ./query-trino.sh "SELECT ..."       # Run a specific query
#   ./query-trino.sh --recent           # Show recent events from all tables
#   ./query-trino.sh --stats            # Show table statistics

set -e

CATALOG="iceberg"
SCHEMA="analytics_logs"

if [ "$1" == "--recent" ]; then
    echo "=== Recent User Events ==="
    docker exec trino trino --catalog=$CATALOG --schema=$SCHEMA --execute \
        "SELECT * FROM user_events ORDER BY timestamp DESC LIMIT 10;"
    
    echo ""
    echo "=== Recent User Activity ==="
    docker exec trino trino --catalog=$CATALOG --schema=$SCHEMA --execute \
        "SELECT * FROM user_activity ORDER BY timestamp DESC LIMIT 10;"
    
    echo ""
    echo "=== Recent API Metrics ==="
    docker exec trino trino --catalog=$CATALOG --schema=$SCHEMA --execute \
        "SELECT * FROM api_metrics ORDER BY timestamp DESC LIMIT 10;"

elif [ "$1" == "--stats" ]; then
    echo "=== Table Statistics ==="
    docker exec trino trino --catalog=$CATALOG --schema=$SCHEMA --execute \
        "SELECT 'user_events' as table_name, COUNT(*) as row_count FROM user_events
         UNION ALL
         SELECT 'user_activity', COUNT(*) FROM user_activity
         UNION ALL
         SELECT 'api_metrics', COUNT(*) FROM api_metrics;"

elif [ -n "$1" ]; then
    # Run the provided query
    docker exec trino trino --catalog=$CATALOG --schema=$SCHEMA --execute "$1"

else
    # Interactive mode
    echo "==========================================
Trino Query Interface
==========================================

Connected to: $CATALOG.$SCHEMA

Available tables:
  • user_events
  • user_activity
  • api_metrics

Example queries:
  SELECT * FROM user_events LIMIT 10;
  SELECT event_type, COUNT(*) FROM user_events GROUP BY event_type;
  SELECT * FROM api_metrics WHERE response_time_ms > 100;

Type 'quit' or Ctrl+D to exit.
==========================================
"
    docker exec -it trino trino --catalog=$CATALOG --schema=$SCHEMA
fi
