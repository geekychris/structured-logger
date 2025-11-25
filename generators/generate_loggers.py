#!/usr/bin/env python3
"""
Code generator for structured loggers.
Generates type-safe Java and Python logger wrappers from log config files.
"""

import argparse
import json
import os
from pathlib import Path
from typing import Dict, List, Any


TYPE_MAPPING = {
    "java": {
        "string": "String",
        "int": "Integer",
        "long": "Long",
        "float": "Float",
        "double": "Double",
        "boolean": "Boolean",
        "timestamp": "Instant",
        "date": "LocalDate",
        "array<string>": "List<String>",
        "array<int>": "List<Integer>",
        "array<long>": "List<Long>",
        "map<string,string>": "Map<String, String>",
    },
    "python": {
        "string": "str",
        "int": "int",
        "long": "int",
        "float": "float",
        "double": "float",
        "boolean": "bool",
        "timestamp": "datetime",
        "date": "date",
        "array<string>": "List[str]",
        "array<int>": "List[int]",
        "array<long>": "List[int]",
        "map<string,string>": "Dict[str, str]",
    },
}


def to_camel_case(snake_str: str) -> str:
    """Convert snake_case to camelCase."""
    components = snake_str.split("_")
    return components[0] + "".join(x.title() for x in components[1:])


def to_pascal_case(snake_str: str) -> str:
    """Convert snake_case to PascalCase."""
    return "".join(x.title() for x in snake_str.split("_"))


def generate_java_logger(config: Dict[str, Any], output_dir: Path) -> None:
    """Generate Java logger wrapper from config."""
    class_name = config["name"]
    topic = config["kafka"]["topic"]
    fields = config["fields"]

    # Generate field declarations
    field_declarations = []
    for field in fields:
        java_type = TYPE_MAPPING["java"][field["type"]]
        field_name = to_camel_case(field["name"])
        field_declarations.append(f"    private final {java_type} {field_name};")

    # Generate constructor parameters
    constructor_params = []
    for field in fields:
        java_type = TYPE_MAPPING["java"][field["type"]]
        field_name = to_camel_case(field["name"])
        constructor_params.append(f"{java_type} {field_name}")

    # Generate constructor assignments
    constructor_assignments = []
    for field in fields:
        field_name = to_camel_case(field["name"])
        constructor_assignments.append(f"        this.{field_name} = {field_name};")

    # Generate builder setters
    builder_setters = []
    for field in fields:
        java_type = TYPE_MAPPING["java"][field["type"]]
        field_name = to_camel_case(field["name"])
        setter_name = field_name
        builder_setters.append(
            f"""        public Builder {setter_name}({java_type} {field_name}) {{
            this.{field_name} = {field_name};
            return this;
        }}"""
        )

    # Generate log method parameter
    partition_key_field = None
    for field in fields:
        if "user_id" in field["name"].lower() or "session_id" in field["name"].lower():
            partition_key_field = to_camel_case(field["name"])
            break
    if not partition_key_field:
        partition_key_field = to_camel_case(fields[0]["name"])

    # Generate imports
    imports = ["java.time.Instant", "java.time.LocalDate"]
    has_list = any("array" in f["type"] for f in fields)
    has_map = any("map" in f["type"] for f in fields)
    if has_list:
        imports.extend(["java.util.List", "java.util.ArrayList"])
    if has_map:
        imports.extend(["java.util.Map", "java.util.HashMap"])

    java_code = f"""package com.logging.generated;

import com.logging.BaseStructuredLogger;
import com.fasterxml.jackson.annotation.JsonProperty;
{chr(10).join(f'import {imp};' for imp in sorted(set(imports)))}

/**
 * Generated structured logger for {config.get('description', class_name)}.
 * 
 * Version: {config['version']}
 * Kafka Topic: {topic}
 * Warehouse Table: {config['warehouse']['table_name']}
 * 
 * DO NOT EDIT - This file is auto-generated from the log config.
 */
public class {class_name}Logger extends BaseStructuredLogger {{

    private static final String TOPIC_NAME = "{topic}";
    private static final String LOGGER_NAME = "{class_name}";

    public {class_name}Logger() {{
        super(TOPIC_NAME, LOGGER_NAME);
    }}

    public {class_name}Logger(String kafkaBootstrapServers) {{
        super(TOPIC_NAME, LOGGER_NAME, kafkaBootstrapServers);
    }}

    /**
     * Log a {class_name} event.
     */
    public void log({", ".join(constructor_params)}) {{
        LogRecord record = new LogRecord({", ".join(to_camel_case(f["name"]) for f in fields)});
        publish({partition_key_field}, record);
    }}

    /**
     * Create a builder for constructing log records.
     */
    public static Builder builder() {{
        return new Builder();
    }}

    /**
     * Log record data class.
     */
    public static class LogRecord {{
{chr(10).join(field_declarations)}

        public LogRecord({", ".join(constructor_params)}) {{
{chr(10).join(constructor_assignments)}
        }}

        // Getters
{chr(10).join(f'        @JsonProperty("{f["name"]}")' + chr(10) + f'        public {TYPE_MAPPING["java"][f["type"]]} get{to_pascal_case(f["name"])}() {{ return {to_camel_case(f["name"])}; }}' for f in fields)}
    }}

    /**
     * Builder for creating log records.
     */
    public static class Builder {{
{chr(10).join(f'        private {TYPE_MAPPING["java"][f["type"]]} {to_camel_case(f["name"])};' for f in fields)}

{chr(10).join(builder_setters)}

        public LogRecord build() {{
            return new LogRecord({", ".join(to_camel_case(f["name"]) for f in fields)});
        }}
    }}
}}
"""

    # Write to file
    output_file = output_dir / f"{class_name}Logger.java"
    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_text(java_code)
    print(f"Generated Java logger: {output_file}")


def generate_python_logger(config: Dict[str, Any], output_dir: Path) -> None:
    """Generate Python logger wrapper from config."""
    class_name = config["name"]
    topic = config["kafka"]["topic"]
    fields = config["fields"]

    # Generate field parameters with types
    field_params = []
    for field in fields:
        python_type = TYPE_MAPPING["python"][field["type"]]
        field_name = field["name"]
        if not field.get("required", True):
            field_params.append(f"{field_name}: Optional[{python_type}] = None")
        else:
            field_params.append(f"{field_name}: {python_type}")

    # Find partition key
    partition_key_field = None
    for field in fields:
        if "user_id" in field["name"].lower() or "session_id" in field["name"].lower():
            partition_key_field = field["name"]
            break
    if not partition_key_field:
        partition_key_field = fields[0]["name"]

    # Generate imports
    imports = ["from datetime import datetime, date", "from typing import Optional, Dict, List, Any"]

    # Build builder methods separately to avoid nested f-string issues
    builder_methods = []
    for field in fields:
        python_type = TYPE_MAPPING["python"][field["type"]]
        field_name = field["name"]
        method = f'''    def {field_name}(self, value: {python_type}) -> "{class_name}LoggerBuilder":
        """Set {field_name}."""
        self._{field_name} = value
        return self'''
        builder_methods.append(method)

    python_code = f'''"""
Generated structured logger for {config.get('description', class_name)}.

Version: {config['version']}
Kafka Topic: {topic}
Warehouse Table: {config['warehouse']['table_name']}

DO NOT EDIT - This file is auto-generated from the log config.
"""

{chr(10).join(imports)}
from structured_logging.base_logger import BaseStructuredLogger


class {class_name}Logger(BaseStructuredLogger):
    """Structured logger for {class_name} events."""

    def __init__(self, kafka_bootstrap_servers: Optional[str] = None):
        """Initialize the {class_name} logger."""
        super().__init__(
            topic_name="{topic}",
            logger_name="{class_name}",
            kafka_bootstrap_servers=kafka_bootstrap_servers,
        )

    def log(
        self,
        {", ".join(field_params)},
    ) -> None:
        """
        Log a {class_name} event.

        Args:
{chr(10).join(f'            {f["name"]}: {f.get("description", f["name"])}' for f in fields)}
        """
        record = {{
{chr(10).join(f'            "{f["name"]}": {f["name"]},' for f in fields)}
        }}

        # Remove None values for optional fields
        record = {{k: v for k, v in record.items() if v is not None}}

        self.publish(key={partition_key_field}, log_record=record)

    @classmethod
    def builder(cls) -> "{class_name}LoggerBuilder":
        """Create a builder for constructing log records."""
        return {class_name}LoggerBuilder()


class {class_name}LoggerBuilder:
    """Builder for {class_name} log records."""

    def __init__(self):
        """Initialize the builder."""
{chr(10).join(f'        self._{f["name"]}: Optional[{TYPE_MAPPING["python"][f["type"]]}] = None' for f in fields)}

{chr(10).join(builder_methods)}

    def build(self) -> Dict[str, Any]:
        """Build and return the log record dictionary."""
        record = {{
{chr(10).join(f'            "{f["name"]}": self._{f["name"]},' for f in fields)}
        }}
        return {{k: v for k, v in record.items() if v is not None}}
'''

    # Write to file
    output_file = output_dir / f"{class_name.lower()}_logger.py"
    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_text(python_code)
    print(f"Generated Python logger: {output_file}")


def main():
    """Main entry point for the generator."""
    parser = argparse.ArgumentParser(
        description="Generate structured logger code from config files"
    )
    parser.add_argument(
        "config_file", help="Path to the log config JSON file"
    )
    parser.add_argument(
        "--java-output",
        default="./java-logger/src/main/java/com/logging/generated",
        help="Output directory for Java code",
    )
    parser.add_argument(
        "--python-output",
        default="./python-logger/structured_logging/generated",
        help="Output directory for Python code",
    )
    parser.add_argument(
        "--lang",
        choices=["java", "python", "both"],
        default="both",
        help="Which language(s) to generate",
    )

    args = parser.parse_args()

    # Load config
    with open(args.config_file, "r") as f:
        config = json.load(f)

    # Generate code
    if args.lang in ["java", "both"]:
        generate_java_logger(config, Path(args.java_output))

    if args.lang in ["python", "both"]:
        generate_python_logger(config, Path(args.python_output))

    print(f"\\nCode generation complete for {config['name']}")


if __name__ == "__main__":
    main()
