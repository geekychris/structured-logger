#!/bin/bash
set -e

echo "=========================================="
echo "Structured Logging Quick Start"
echo "=========================================="
echo ""

# Step 1: Generate loggers
echo "Step 1: Generating loggers from example configs..."
cd generators
python3 generate_loggers.py ../examples/user_events.json
python3 generate_loggers.py ../examples/api_metrics.json
cd ..
echo "✓ Loggers generated"
echo ""

# Step 2: Show generated files
echo "Step 2: Generated files:"
echo "Java loggers:"
ls -la java-logger/src/main/java/com/logging/generated/ 2>/dev/null || echo "  (will be created after generation)"
echo ""
echo "Python loggers:"
ls -la python-logger/structured_logging/generated/ 2>/dev/null || echo "  (will be created after generation)"
echo ""

# Step 3: Instructions
echo "=========================================="
echo "Next Steps:"
echo "=========================================="
echo ""
echo "1. Build the components:"
echo "   make build-all"
echo ""
echo "2. Start Kafka (if not already running):"
echo "   # Start Kafka on localhost:9092"
echo ""
echo "3. Run the Spark consumer:"
echo "   cd spark-consumer"
echo "   spark-submit \\"
echo "     --class com.logging.consumer.StructuredLogConsumer \\"
echo "     --master local[*] \\"
echo "     target/structured-log-consumer-1.0.0.jar \\"
echo "     ../examples/ \\"
echo "     localhost:9092 \\"
echo "     /tmp/warehouse"
echo ""
echo "4. Use the loggers in your application:"
echo "   - Java: See examples/JavaExample.java"
echo "   - Python: See examples/python_example.py"
echo ""
echo "=========================================="
echo "Quick Start Complete!"
echo "=========================================="
