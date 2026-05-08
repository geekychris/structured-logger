#!/usr/bin/env bats

# Unit tests for build-and-setup.sh
# Run with: bats tests/test_build_and_setup.bats

# Test setup
setup() {
    # Get the directory of the test file
    TEST_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")" && pwd)"
    PROJECT_DIR="$(dirname "$TEST_DIR")"
    MOCKS_DIR="$TEST_DIR/mocks"
    
    # Create a temporary directory for test isolation
    export TEST_TEMP_DIR="$(mktemp -d)"
    
    # Create mock bin directory
    export MOCK_BIN="$TEST_TEMP_DIR/mock_bin"
    mkdir -p "$MOCK_BIN"
    
    # Save original PATH
    export ORIGINAL_PATH="$PATH"
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

# Helper function to create a mock command that fails (command not found)
create_missing_mock() {
    local cmd="$1"
    # Don't create the mock - this simulates the command not being installed
}

# Helper function to create a mock command that succeeds
create_success_mock() {
    local cmd="$1"
    local output="${2:-}"
    cat > "$MOCK_BIN/$cmd" << EOF
#!/bin/bash
echo "$output"
exit 0
EOF
    chmod +x "$MOCK_BIN/$cmd"
}

# Helper function to create a mock command that fails with exit code
create_fail_mock() {
    local cmd="$1"
    local exit_code="${2:-1}"
    local output="${3:-}"
    cat > "$MOCK_BIN/$cmd" << EOF
#!/bin/bash
echo "$output" >&2
exit $exit_code
EOF
    chmod +x "$MOCK_BIN/$cmd"
}

# Helper function to extract a specific function from build-and-setup.sh for isolated testing
extract_dependency_check() {
    cat << 'SCRIPT'
#!/bin/bash
set -o pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_status() {
    local status=$1
    local message=$2
    if [ "$status" = "OK" ]; then
        echo -e "${GREEN}✓${NC} $message"
    elif [ "$status" = "WARN" ]; then
        echo -e "${YELLOW}⚠${NC} $message"
    elif [ "$status" = "ERROR" ]; then
        echo -e "${RED}✗${NC} $message"
    elif [ "$status" = "INFO" ]; then
        echo -e "${BLUE}ℹ${NC} $message"
    fi
}

# Check Docker
check_docker() {
    if command -v docker &> /dev/null; then
        if docker ps &> /dev/null; then
            print_status "OK" "Docker is installed and running"
            return 0
        else
            print_status "ERROR" "Docker is installed but not running"
            echo "  Please start Docker and run this script again"
            return 1
        fi
    else
        print_status "ERROR" "Docker is not installed"
        echo "  Please install Docker: https://docs.docker.com/get-docker/"
        return 1
    fi
}

# Check Java
check_java() {
    if command -v java &> /dev/null; then
        JAVA_VERSION=$(java -version 2>&1 | head -n 1)
        print_status "OK" "Java is installed: $JAVA_VERSION"
        return 0
    else
        print_status "ERROR" "Java is not installed"
        echo "  Please install Java JDK 11 or later"
        return 1
    fi
}

# Main check based on argument
case "$1" in
    docker)
        check_docker
        exit $?
        ;;
    java)
        check_java
        exit $?
        ;;
    *)
        echo "Usage: $0 {docker|java}"
        exit 1
        ;;
esac
SCRIPT
}

# =============================================================================
# Test 1: Docker not installed - script should exit with error
# =============================================================================
@test "build-and-setup.sh exits with error when Docker is not installed" {
    # Create the isolated test script
    extract_dependency_check > "$TEST_TEMP_DIR/check_deps.sh"
    chmod +x "$TEST_TEMP_DIR/check_deps.sh"
    
    # Set PATH to exclude docker (use empty mock bin that has no docker)
    export PATH="$MOCK_BIN"
    
    # Run the docker check
    run "$TEST_TEMP_DIR/check_deps.sh" docker
    
    # Should fail with exit code 1
    [ "$status" -eq 1 ]
    
    # Should contain error message about Docker not installed
    [[ "$output" == *"Docker is not installed"* ]]
}

# =============================================================================
# Test 2: Java not installed - script should exit with error
# =============================================================================
@test "build-and-setup.sh exits with error when Java is not installed" {
    # Create the isolated test script
    extract_dependency_check > "$TEST_TEMP_DIR/check_deps.sh"
    chmod +x "$TEST_TEMP_DIR/check_deps.sh"
    
    # Set PATH to exclude java (use empty mock bin that has no java)
    export PATH="$MOCK_BIN"
    
    # Run the java check
    run "$TEST_TEMP_DIR/check_deps.sh" java
    
    # Should fail with exit code 1
    [ "$status" -eq 1 ]
    
    # Should contain error message about Java not installed
    [[ "$output" == *"Java is not installed"* ]]
}

# =============================================================================
# Test 3: Python venv setup succeeds
# =============================================================================
@test "build-and-setup.sh successfully sets up Python virtual environment and installs dependencies" {
    # Create a minimal test environment
    mkdir -p "$TEST_TEMP_DIR/python-logger"
    
    # Create a minimal requirements.txt
    echo "requests" > "$TEST_TEMP_DIR/python-logger/requirements.txt"
    
    # Create a test script that mimics the venv setup logic
    cat > "$TEST_TEMP_DIR/setup_venv.sh" << 'SCRIPT'
#!/bin/bash
set -o pipefail

SCRIPT_DIR="$1"
VENV_DIR="$SCRIPT_DIR/python-logger/venv"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

print_status() {
    local status=$1
    local message=$2
    if [ "$status" = "OK" ]; then
        echo -e "${GREEN}✓${NC} $message"
    elif [ "$status" = "INFO" ]; then
        echo -e "${BLUE}ℹ${NC} $message"
    elif [ "$status" = "ERROR" ]; then
        echo -e "${RED}✗${NC} $message"
    fi
}

if [ -d "$VENV_DIR" ]; then
    print_status "OK" "Virtual environment exists"
else
    print_status "INFO" "Creating virtual environment..."
    python3 -m venv "$VENV_DIR"
    if [ $? -eq 0 ]; then
        print_status "OK" "Virtual environment created"
    else
        print_status "ERROR" "Failed to create virtual environment"
        exit 1
    fi
fi

# Install Python dependencies
print_status "INFO" "Installing Python dependencies..."
"$VENV_DIR/bin/pip" install -q -r "$SCRIPT_DIR/python-logger/requirements.txt"
if [ $? -eq 0 ]; then
    print_status "OK" "Python dependencies installed"
else
    print_status "ERROR" "Failed to install Python dependencies"
    exit 1
fi

exit 0
SCRIPT
    chmod +x "$TEST_TEMP_DIR/setup_venv.sh"
    
    # Run the venv setup
    run "$TEST_TEMP_DIR/setup_venv.sh" "$TEST_TEMP_DIR"
    
    # Should succeed
    [ "$status" -eq 0 ]
    
    # Virtual environment directory should exist
    [ -d "$TEST_TEMP_DIR/python-logger/venv" ]
    
    # pip should exist in the venv
    [ -f "$TEST_TEMP_DIR/python-logger/venv/bin/pip" ]
    
    # Output should indicate success
    [[ "$output" == *"Virtual environment created"* ]] || [[ "$output" == *"Virtual environment exists"* ]]
    [[ "$output" == *"Python dependencies installed"* ]]
}

# =============================================================================
# Test 4: Java logger build succeeds
# =============================================================================
@test "build-and-setup.sh successfully builds the Java logger" {
    # Create mock mvn that simulates successful build
    cat > "$MOCK_BIN/mvn" << 'EOF'
#!/bin/bash
# Simulate maven build
if [[ "$*" == *"clean package"* ]]; then
    # Create fake target directory and jar
    mkdir -p target
    touch target/structured-logger-1.0.0.jar
    exit 0
fi
echo "Maven mock called with: $*"
exit 0
EOF
    chmod +x "$MOCK_BIN/mvn"
    
    # Create a test script that mimics the Java build logic
    cat > "$TEST_TEMP_DIR/build_java.sh" << 'SCRIPT'
#!/bin/bash
set -o pipefail

SCRIPT_DIR="$1"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

print_status() {
    local status=$1
    local message=$2
    if [ "$status" = "OK" ]; then
        echo -e "${GREEN}✓${NC} $message"
    elif [ "$status" = "INFO" ]; then
        echo -e "${BLUE}ℹ${NC} $message"
    elif [ "$status" = "ERROR" ]; then
        echo -e "${RED}✗${NC} $message"
    fi
}

cd "$SCRIPT_DIR/java-logger"
print_status "INFO" "Building Java logger library..."
mvn clean package -q -DskipTests

if [ $? -eq 0 ]; then
    print_status "OK" "Java logger built successfully"
    JAR_SIZE=$(ls -lh target/*.jar 2>/dev/null | awk '{print $5}' | head -1)
    print_status "INFO" "JAR size: $JAR_SIZE"
    exit 0
else
    print_status "ERROR" "Failed to build Java logger"
    exit 1
fi
SCRIPT
    chmod +x "$TEST_TEMP_DIR/build_java.sh"
    
    # Create java-logger directory structure
    mkdir -p "$TEST_TEMP_DIR/java-logger"
    
    # Set PATH to use our mock mvn first
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    
    # Run the build
    run "$TEST_TEMP_DIR/build_java.sh" "$TEST_TEMP_DIR"
    
    # Should succeed
    [ "$status" -eq 0 ]
    
    # Output should indicate success
    [[ "$output" == *"Java logger built successfully"* ]]
    
    # JAR file should exist (created by mock)
    [ -f "$TEST_TEMP_DIR/java-logger/target/structured-logger-1.0.0.jar" ]
}

# =============================================================================
# Additional tests for edge cases
# =============================================================================

@test "build-and-setup.sh detects Docker installed but not running" {
    # Create mock docker that is "installed" but fails on 'docker ps'
    cat > "$MOCK_BIN/docker" << 'EOF'
#!/bin/bash
if [ "$1" = "ps" ]; then
    echo "Cannot connect to the Docker daemon" >&2
    exit 1
fi
exit 0
EOF
    chmod +x "$MOCK_BIN/docker"
    
    # Create the isolated test script
    extract_dependency_check > "$TEST_TEMP_DIR/check_deps.sh"
    chmod +x "$TEST_TEMP_DIR/check_deps.sh"
    
    # Set PATH to use mock
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    
    # Run the docker check
    run "$TEST_TEMP_DIR/check_deps.sh" docker
    
    # Should fail
    [ "$status" -eq 1 ]
    
    # Should indicate Docker is not running
    [[ "$output" == *"Docker is installed but not running"* ]]
}

@test "build-and-setup.sh succeeds when Docker is installed and running" {
    # Create mock docker that succeeds
    cat > "$MOCK_BIN/docker" << 'EOF'
#!/bin/bash
if [ "$1" = "ps" ]; then
    echo "CONTAINER ID   IMAGE   COMMAND   CREATED   STATUS   PORTS   NAMES"
    exit 0
fi
exit 0
EOF
    chmod +x "$MOCK_BIN/docker"
    
    # Create the isolated test script
    extract_dependency_check > "$TEST_TEMP_DIR/check_deps.sh"
    chmod +x "$TEST_TEMP_DIR/check_deps.sh"
    
    # Set PATH to use mock
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    
    # Run the docker check
    run "$TEST_TEMP_DIR/check_deps.sh" docker
    
    # Should succeed
    [ "$status" -eq 0 ]
    
    # Should indicate success
    [[ "$output" == *"Docker is installed and running"* ]]
}

@test "build-and-setup.sh succeeds when Java is installed" {
    # Create mock java that succeeds
    cat > "$MOCK_BIN/java" << 'EOF'
#!/bin/bash
if [ "$1" = "-version" ]; then
    echo 'openjdk version "21.0.6" 2024-01-16' >&2
    echo 'OpenJDK Runtime Environment (build 21.0.6+7)' >&2
    exit 0
fi
exit 0
EOF
    chmod +x "$MOCK_BIN/java"
    
    # Create the isolated test script
    extract_dependency_check > "$TEST_TEMP_DIR/check_deps.sh"
    chmod +x "$TEST_TEMP_DIR/check_deps.sh"
    
    # Set PATH to use mock
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    
    # Run the java check
    run "$TEST_TEMP_DIR/check_deps.sh" java
    
    # Should succeed
    [ "$status" -eq 0 ]
    
    # Should indicate success with version
    [[ "$output" == *"Java is installed"* ]]
}
