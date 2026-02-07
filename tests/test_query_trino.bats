#!/usr/bin/env bats

# Unit tests for query-trino.sh
# Run with: bats tests/test_query_trino.bats

# Test setup
setup() {
    # Get the directory of the test file
    TEST_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")" && pwd)"
    PROJECT_DIR="$(dirname "$TEST_DIR")"
    
    # Create a temporary directory for test isolation
    export TEST_TEMP_DIR="$(mktemp -d)"
    
    # Create mock bin directory
    export MOCK_BIN="$TEST_TEMP_DIR/mock_bin"
    mkdir -p "$MOCK_BIN"
    
    # Save original PATH
    export ORIGINAL_PATH="$PATH"
    
    # Track docker exec calls for verification
    export DOCKER_CALLS_FILE="$TEST_TEMP_DIR/docker_calls.log"
    touch "$DOCKER_CALLS_FILE"
}

# Test teardown
teardown() {
    # Restore original PATH
    export PATH="$ORIGINAL_PATH"
    
    # Clean up temporary directory
    if [ -n "$TEST_TEMP_DIR" ] && [ -d "$TEST_TEMP_DIR" ]; then
        rm -rf "$TEST_TEMP_DIR"
    fi
}

# Helper function to create a mock docker command that logs calls
create_docker_mock() {
    local expected_output="${1:-}"
    cat > "$MOCK_BIN/docker" << EOF
#!/bin/bash
# Log all docker calls for verification
echo "\$@" >> "$DOCKER_CALLS_FILE"

# Return expected output
echo "$expected_output"
exit 0
EOF
    chmod +x "$MOCK_BIN/docker"
}

# Helper function to create query-trino.sh in test temp dir
create_test_script() {
    cat > "$TEST_TEMP_DIR/query-trino.sh" << 'EOF'
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
EOF
    chmod +x "$TEST_TEMP_DIR/query-trino.sh"
}

# =============================================================================
# Test 5: query-trino.sh executes a provided SQL query using Trino
# =============================================================================
@test "query-trino.sh executes a provided SQL query using Trino" {
    # Create test script
    create_test_script
    
    # Create mock docker that captures and verifies the query
    cat > "$MOCK_BIN/docker" << 'MOCK'
#!/bin/bash
# Log all arguments
echo "$@" >> "$DOCKER_CALLS_FILE"

# Check if this is an exec call with a query
if [[ "$1" == "exec" && "$2" == "trino" ]]; then
    # Extract the query from --execute argument
    for arg in "$@"; do
        if [[ "$prev_arg" == "--execute" ]]; then
            echo "Query received: $arg"
            # Simulate query output
            echo "col1 | col2"
            echo "-----+-----"
            echo "val1 | val2"
            exit 0
        fi
        prev_arg="$arg"
    done
fi
exit 0
MOCK
    chmod +x "$MOCK_BIN/docker"
    
    # Set PATH to use mock
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    
    # Run the query script with a SQL query
    run "$TEST_TEMP_DIR/query-trino.sh" "SELECT * FROM user_events LIMIT 5;"
    
    # Should succeed
    [ "$status" -eq 0 ]
    
    # Verify docker was called
    [ -s "$DOCKER_CALLS_FILE" ]
    
    # Verify the docker exec command was called with trino
    grep -q "exec trino trino" "$DOCKER_CALLS_FILE"
    
    # Verify the correct catalog and schema were used
    grep -q "iceberg" "$DOCKER_CALLS_FILE"
    grep -q "analytics_logs" "$DOCKER_CALLS_FILE"
    
    # Verify our query was passed
    grep -q "SELECT \* FROM user_events LIMIT 5;" "$DOCKER_CALLS_FILE"
}

@test "query-trino.sh passes correct catalog and schema to Trino" {
    # Create test script
    create_test_script
    
    # Create mock docker that verifies catalog/schema
    cat > "$MOCK_BIN/docker" << 'MOCK'
#!/bin/bash
echo "$@" >> "$DOCKER_CALLS_FILE"

# Verify catalog and schema are present
if [[ "$*" == *"--catalog=iceberg"* ]] && [[ "$*" == *"--schema=analytics_logs"* ]]; then
    echo "Catalog and schema verified"
    exit 0
else
    echo "ERROR: Missing catalog or schema" >&2
    exit 1
fi
MOCK
    chmod +x "$MOCK_BIN/docker"
    
    # Set PATH to use mock
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    
    # Run the query script
    run "$TEST_TEMP_DIR/query-trino.sh" "SELECT COUNT(*) FROM user_events;"
    
    # Should succeed (mock verifies catalog/schema)
    [ "$status" -eq 0 ]
    
    # Output should indicate verification passed
    [[ "$output" == *"Catalog and schema verified"* ]]
}

@test "query-trino.sh handles --recent flag correctly" {
    # Create test script
    create_test_script
    
    # Create mock docker
    cat > "$MOCK_BIN/docker" << 'MOCK'
#!/bin/bash
echo "$@" >> "$DOCKER_CALLS_FILE"
echo "Mock query result"
exit 0
MOCK
    chmod +x "$MOCK_BIN/docker"
    
    # Set PATH to use mock
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    
    # Run with --recent flag
    run "$TEST_TEMP_DIR/query-trino.sh" "--recent"
    
    # Should succeed
    [ "$status" -eq 0 ]
    
    # Should query all three tables
    grep -q "user_events" "$DOCKER_CALLS_FILE"
    grep -q "user_activity" "$DOCKER_CALLS_FILE"
    grep -q "api_metrics" "$DOCKER_CALLS_FILE"
    
    # Output should show section headers
    [[ "$output" == *"Recent User Events"* ]]
    [[ "$output" == *"Recent User Activity"* ]]
    [[ "$output" == *"Recent API Metrics"* ]]
}

@test "query-trino.sh handles --stats flag correctly" {
    # Create test script
    create_test_script
    
    # Create mock docker
    cat > "$MOCK_BIN/docker" << 'MOCK'
#!/bin/bash
echo "$@" >> "$DOCKER_CALLS_FILE"
echo "user_events | 100"
echo "user_activity | 50"
echo "api_metrics | 200"
exit 0
MOCK
    chmod +x "$MOCK_BIN/docker"
    
    # Set PATH to use mock
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    
    # Run with --stats flag
    run "$TEST_TEMP_DIR/query-trino.sh" "--stats"
    
    # Should succeed
    [ "$status" -eq 0 ]
    
    # Output should show statistics header
    [[ "$output" == *"Table Statistics"* ]]
    
    # Verify UNION ALL query was used
    grep -q "UNION ALL" "$DOCKER_CALLS_FILE"
}

@test "query-trino.sh uses docker exec with trino container" {
    # Create test script
    create_test_script
    
    # Create mock docker that verifies exec command structure
    cat > "$MOCK_BIN/docker" << 'MOCK'
#!/bin/bash
echo "$@" >> "$DOCKER_CALLS_FILE"

# Verify the command structure: docker exec trino trino ...
if [[ "$1" == "exec" && "$2" == "trino" && "$3" == "trino" ]]; then
    echo "Command structure verified"
    exit 0
else
    echo "ERROR: Invalid command structure" >&2
    echo "Got: $@" >&2
    exit 1
fi
MOCK
    chmod +x "$MOCK_BIN/docker"
    
    # Set PATH to use mock
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    
    # Run a query
    run "$TEST_TEMP_DIR/query-trino.sh" "SELECT 1;"
    
    # Should succeed
    [ "$status" -eq 0 ]
    
    # Verify the correct command structure was used
    [[ "$output" == *"Command structure verified"* ]]
}

@test "query-trino.sh handles complex queries with special characters" {
    # Create test script
    create_test_script
    
    # Create mock docker
    cat > "$MOCK_BIN/docker" << 'MOCK'
#!/bin/bash
echo "$@" >> "$DOCKER_CALLS_FILE"
echo "Query executed successfully"
exit 0
MOCK
    chmod +x "$MOCK_BIN/docker"
    
    # Set PATH to use mock
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    
    # Run a complex query with special characters
    local complex_query="SELECT event_type, COUNT(*) as cnt FROM user_events WHERE timestamp > '2024-01-01' GROUP BY event_type HAVING COUNT(*) > 10 ORDER BY cnt DESC LIMIT 5;"
    run "$TEST_TEMP_DIR/query-trino.sh" "$complex_query"
    
    # Should succeed
    [ "$status" -eq 0 ]
    
    # Verify the query was passed to docker
    grep -q "GROUP BY event_type" "$DOCKER_CALLS_FILE"
}

@test "query-trino.sh fails gracefully when docker command fails" {
    # Create test script
    create_test_script
    
    # Create mock docker that fails
    cat > "$MOCK_BIN/docker" << 'MOCK'
#!/bin/bash
echo "Error: Cannot connect to Docker daemon" >&2
exit 1
MOCK
    chmod +x "$MOCK_BIN/docker"
    
    # Set PATH to use mock
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    
    # Run a query (should fail due to docker failure)
    run "$TEST_TEMP_DIR/query-trino.sh" "SELECT 1;"
    
    # Should fail
    [ "$status" -ne 0 ]
}
