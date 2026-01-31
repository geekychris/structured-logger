.PHONY: help generate build-java build-python build-spark build-all clean examples

help:
	@echo "Structured Logging System - Make Targets"
	@echo ""
	@echo "  generate         - Generate loggers from log-configs/"
	@echo "  build-java       - Build Java logger library"
	@echo "  build-python     - Install Python logger dependencies"
	@echo "  build-spark      - Build Spark consumer JAR"
	@echo "  build-all        - Build all components"
	@echo "  clean            - Clean generated files and build artifacts"
	@echo "  examples         - Run example code"
	@echo ""
	@echo "For detailed instructions on adding new loggers, see: ADDING_NEW_LOGGERS.md"
	@echo ""

generate:
	@echo "Generating loggers from log-configs/..."
	@echo "Note: Add your config files to log-configs/ directory"
	@for config in log-configs/*.json; do \
		echo "Generating from $$config..."; \
		python3 generators/generate_loggers.py "$$config"; \
	done
	@echo "Generation complete! Generated loggers for all configs in log-configs/"

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
