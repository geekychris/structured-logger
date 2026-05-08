# Tests

This directory contains tests for the structured-logger project.

## Test Files

- `test_build_and_setup.bats` - Unit tests for `build-and-setup.sh`
- `test_query_trino.bats` - Unit tests for `query-trino.sh`
- `test_envelope_integration.py` - Integration tests for envelope format

## Running Shell Script Tests (bats)

### Prerequisites

Install bats-core (Bash Automated Testing System):

```bash
# Ubuntu/Debian
sudo apt-get install bats

# macOS with Homebrew
brew install bats-core

# Or install from source
git clone https://github.com/bats-core/bats-core.git
cd bats-core
sudo ./install.sh /usr/local
```

### Running Tests

Run all bats tests:
```bash
bats tests/*.bats
```

Run specific test file:
```bash
bats tests/test_build_and_setup.bats
bats tests/test_query_trino.bats
```

Run with verbose output:
```bash
bats --verbose-run tests/*.bats
```

Run with TAP output (for CI):
```bash
bats --tap tests/*.bats
```

## Test Coverage

### build-and-setup.sh Tests

1. **Docker not installed** - Verifies script exits with error when Docker is missing
2. **Java not installed** - Verifies script exits with error when Java is missing
3. **Python venv setup** - Verifies virtual environment creation and dependency installation
4. **Java logger build** - Verifies Maven build process for Java logger
5. **Docker installed but not running** - Edge case: Docker daemon not running
6. **Docker installed and running** - Success case for Docker check
7. **Java installed** - Success case for Java check

### query-trino.sh Tests

1. **Execute SQL query** - Verifies queries are passed correctly to Trino via Docker
2. **Catalog and schema** - Verifies correct catalog/schema are used
3. **--recent flag** - Verifies recent events query across all tables
4. **--stats flag** - Verifies table statistics query
5. **Docker exec structure** - Verifies correct docker exec command format
6. **Complex queries** - Verifies handling of special characters in queries
7. **Error handling** - Verifies graceful failure when Docker fails

## Running Integration Tests

The integration tests require the full infrastructure to be running:

```bash
# Start the infrastructure first
docker-compose up -d

# Run integration tests
python3 tests/test_envelope_integration.py
```

## Writing New Tests

### Bats Tests

Bats tests use a simple format:

```bash
@test "description of test" {
    # Setup
    run some_command
    
    # Assertions
    [ "$status" -eq 0 ]
    [[ "$output" == *"expected text"* ]]
}
```

Key points:
- Use `setup()` and `teardown()` functions for test fixtures
- Use `run` to capture command output and exit status
- Use `$status` for exit code and `$output` for stdout
- Mock external commands using PATH manipulation
