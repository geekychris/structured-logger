#!/bin/bash

# Comprehensive Build and Setup Script for Structured Logger
# This script checks dependencies, builds all components, and offers to run tests

set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Track if we need to prompt user
NEEDS_USER_ACTION=false
DOCKER_SERVICES_MISSING=false

echo "=========================================="
echo "Structured Logger - Build & Setup"
echo "=========================================="
echo ""

# Function to print colored status
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

# Function to ask yes/no question
ask_yes_no() {
    local prompt=$1
    local default=${2:-"n"}
    
    if [ "$default" = "y" ]; then
        prompt="$prompt [Y/n]: "
    else
        prompt="$prompt [y/N]: "
    fi
    
    read -p "$prompt" response
    response=${response:-$default}
    
    if [[ "$response" =~ ^[Yy]$ ]]; then
        return 0
    else
        return 1
    fi
}

echo "Step 1: Checking System Dependencies"
echo "--------------------------------------"

# Check Docker
if command -v docker &> /dev/null; then
    if docker ps &> /dev/null; then
        print_status "OK" "Docker is installed and running"
    else
        print_status "ERROR" "Docker is installed but not running"
        echo "  Please start Docker and run this script again"
        exit 1
    fi
else
    print_status "ERROR" "Docker is not installed"
    echo "  Please install Docker: https://docs.docker.com/get-docker/"
    exit 1
fi

# Check Java
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -n 1)
    print_status "OK" "Java is installed: $JAVA_VERSION"
else
    print_status "ERROR" "Java is not installed"
    echo "  Please install Java JDK 11 or later"
    exit 1
fi

# Check Maven
if command -v mvn &> /dev/null; then
    MVN_VERSION=$(mvn -version | head -n 1)
    print_status "OK" "Maven is installed: $MVN_VERSION"
else
    print_status "ERROR" "Maven is not installed"
    echo "  Please install Maven: https://maven.apache.org/install.html"
    exit 1
fi

# Check Python3
if command -v python3 &> /dev/null; then
    PYTHON_VERSION=$(python3 --version)
    print_status "OK" "Python3 is installed: $PYTHON_VERSION"
else
    print_status "ERROR" "Python3 is not installed"
    echo "  Please install Python 3.8 or later"
    exit 1
fi

echo ""
echo "Step 2: Checking Docker Services"
echo "--------------------------------------"

# Check for required Docker containers
REQUIRED_SERVICES=("kafka" "spark" "minio" "hive-metastore" "trino")
MISSING_SERVICES=()

for service in "${REQUIRED_SERVICES[@]}"; do
    if docker ps --format "{{.Names}}" | grep -qi "$service"; then
        CONTAINER_NAME=$(docker ps --format "{{.Names}}" | grep -i "$service" | head -1)
        print_status "OK" "$service is running ($CONTAINER_NAME)"
    else
        print_status "WARN" "$service is not running"
        MISSING_SERVICES+=("$service")
        DOCKER_SERVICES_MISSING=true
    fi
done

if [ "$DOCKER_SERVICES_MISSING" = true ]; then
    echo ""
    echo -e "${YELLOW}=========================================="
    echo "Docker Services Required"
    echo "==========================================${NC}"
    echo ""
    echo "The following services are not running:"
    for service in "${MISSING_SERVICES[@]}"; do
        echo "  • $service"
    done
    echo ""
    echo "This project requires the lakehouse infrastructure to be running."
    echo ""
    echo "To start the services, you have two options:"
    echo ""
    echo "Option 1: Start with included script (if you have trino_docker setup)"
    echo "  ./start-demo-with-trino-docker.sh"
    echo ""
    echo "Option 2: Start with standalone docker-compose"
    echo "  cd ../spark_minio_trino  # or your lakehouse directory"
    echo "  docker-compose up -d"
    echo ""
    echo "After starting the services, run this script again."
    echo ""
    
    if ask_yes_no "Do you want to continue anyway (might fail later)?"; then
        print_status "WARN" "Continuing without all services..."
    else
        echo "Exiting. Please start the required services and run this script again."
        exit 1
    fi
fi

echo ""
echo "Step 3: Python Virtual Environment"
echo "--------------------------------------"

VENV_DIR="$SCRIPT_DIR/python-logger/venv"

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

echo ""
echo "Step 4: Building Java Logger"
echo "--------------------------------------"

cd "$SCRIPT_DIR/java-logger"
print_status "INFO" "Building Java logger library..."
mvn clean package -q -DskipTests

if [ $? -eq 0 ]; then
    print_status "OK" "Java logger built successfully"
    JAR_SIZE=$(ls -lh target/*.jar 2>/dev/null | awk '{print $5}' | head -1)
    print_status "INFO" "JAR size: $JAR_SIZE"
else
    print_status "ERROR" "Failed to build Java logger"
    cd "$SCRIPT_DIR"
    exit 1
fi

cd "$SCRIPT_DIR"

echo ""
echo "Step 5: Building Spark Consumer"
echo "--------------------------------------"

cd "$SCRIPT_DIR/spark-consumer"
print_status "INFO" "Building Spark consumer..."
mvn clean package -q -DskipTests

if [ $? -eq 0 ]; then
    print_status "OK" "Spark consumer built successfully"
    JAR_SIZE=$(ls -lh target/structured-log-consumer-*.jar 2>/dev/null | awk '{print $5}' | head -1)
    print_status "INFO" "JAR size: $JAR_SIZE"
else
    print_status "ERROR" "Failed to build Spark consumer"
    cd "$SCRIPT_DIR"
    exit 1
fi

cd "$SCRIPT_DIR"

echo ""
echo "Step 6: Checking Log Configurations"
echo "--------------------------------------"

CONFIG_COUNT=$(ls -1 log-configs/*.json 2>/dev/null | wc -l | tr -d ' ')

if [ "$CONFIG_COUNT" -gt 0 ]; then
    print_status "OK" "Found $CONFIG_COUNT log configuration(s)"
    for config in log-configs/*.json; do
        echo "    • $(basename $config)"
    done
    
    echo ""
    if ask_yes_no "Do you want to generate/regenerate loggers from configs?" "y"; then
        print_status "INFO" "Generating loggers..."
        for config in log-configs/*.json; do
            echo "  Generating from $(basename $config)..."
            python3 generators/generate_loggers.py "$config"
        done
        print_status "OK" "Logger generation complete"
    else
        print_status "INFO" "Skipping logger generation"
    fi
else
    print_status "WARN" "No log configurations found in log-configs/"
    echo "  You can add your configs to log-configs/ and run: make generate"
fi

echo ""
echo "=========================================="
echo -e "${GREEN}✅ Build Complete!${NC}"
echo "=========================================="
echo ""
echo "All components have been built successfully:"
echo "  ✓ Python virtual environment ready"
echo "  ✓ Java logger library built"
echo "  ✓ Spark consumer built"
echo ""

# Offer to run examples
if [ "$DOCKER_SERVICES_MISSING" = false ]; then
    echo "=========================================="
    echo "Run Tests"
    echo "=========================================="
    echo ""
    echo "Would you like to run a test?"
    echo ""
    echo "  1) Run Java example (sends logs to Kafka)"
    echo "  2) Run Python example (sends logs to Kafka)"
    echo "  3) Run test data generator (continuous)"
    echo "  4) Skip tests"
    echo ""
    read -p "Select option [1-4]: " test_choice
    
    case "$test_choice" in
        1)
            echo ""
            print_status "INFO" "Running Java example..."
            echo ""
            ./run-java-demo.sh
            ;;
        2)
            echo ""
            print_status "INFO" "Running Python example..."
            echo ""
            ./examples/python_example.py
            ;;
        3)
            echo ""
            print_status "INFO" "Starting test data generator..."
            echo "Press Ctrl+C to stop"
            echo ""
            ./examples/generate_test_data.py --continuous --rate 5
            ;;
        4)
            print_status "INFO" "Skipping tests"
            ;;
        *)
            print_status "WARN" "Invalid option, skipping tests"
            ;;
    esac
    
    echo ""
fi

echo ""
echo "=========================================="
echo "Next Steps"
echo "=========================================="
echo ""
echo "To run examples manually:"
echo "  Java:   ./run-java-demo.sh"
echo "  Python: ./examples/python_example.py"
echo ""
echo "To generate test data:"
echo "  ./examples/generate_test_data.py --count 100"
echo "  ./examples/generate_test_data.py --continuous --rate 10"
echo ""
echo "To query data in Trino:"
echo "  docker exec -it trino trino"
echo "  USE local.analytics_logs;"
echo "  SHOW TABLES;"
echo "  SELECT * FROM user_events LIMIT 10;"
echo ""
echo "For more information, see:"
echo "  • README.md - Overview and architecture"
echo "  • QUICKSTART.md - Quick start guide"
echo "  • examples/README.md - Example usage"
echo ""
echo "=========================================="
