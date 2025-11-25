.PHONY: help generate build-java build-python build-spark build-all clean examples

help:
	@echo "Structured Logging System - Make Targets"
	@echo ""
	@echo "  generate         - Generate loggers from example configs"
	@echo "  build-java       - Build Java logger library"
	@echo "  build-python     - Install Python logger dependencies"
	@echo "  build-spark      - Build Spark consumer JAR"
	@echo "  build-all        - Build all components"
	@echo "  clean            - Clean generated files and build artifacts"
	@echo "  examples         - Run example code"
	@echo ""

generate:
	@echo "Generating loggers from example configs..."
	cd generators && python3 generate_loggers.py ../examples/user_events.json
	cd generators && python3 generate_loggers.py ../examples/api_metrics.json
	@echo "Generation complete!"

build-java:
	@echo "Building Java logger..."
	cd java-logger && mvn clean package
	@echo "Java build complete!"

build-python:
	@echo "Installing Python dependencies..."
	cd python-logger && pip install -r requirements.txt
	@echo "Python setup complete!"

build-spark:
	@echo "Building Spark consumer..."
	cd spark-consumer && mvn clean package
	@echo "Spark build complete!"

build-all: build-java build-python build-spark
	@echo "All components built successfully!"

clean:
	@echo "Cleaning build artifacts..."
	rm -rf java-logger/target
	rm -rf java-logger/src/main/java/com/logging/generated
	rm -rf python-logger/structured_logging/generated
	rm -rf spark-consumer/target
	rm -rf spark-consumer/project/target
	@echo "Clean complete!"

examples:
	@echo "Note: Make sure Kafka is running before running examples"
	@echo "Run examples manually:"
	@echo "  Java:   cd examples && javac -cp ../java-logger/target/*:. JavaExample.java && java -cp ../java-logger/target/*:. JavaExample"
	@echo "  Python: cd examples && python3 python_example.py"
