"""Avro envelope-schema derivation. Pure-Python, no fastavro dependency, so
the factory can call this without forcing a fastavro import on users who only
want JSON sinks."""
from __future__ import annotations

from typing import Any, Dict, List


def derive_avro_schema(
    log_type: str, log_class: str, version: str, fields: List[Dict[str, Any]]
) -> Dict[str, Any]:
    avro_type_map = {
        "string": "string",
        "int": "int",
        "long": "long",
        "float": "float",
        "double": "double",
        "boolean": "boolean",
        "timestamp": "string",
        "date": "string",
        "array<string>": {"type": "array", "items": "string"},
        "array<int>": {"type": "array", "items": "int"},
        "array<long>": {"type": "array", "items": "long"},
        "map<string,string>": {"type": "map", "values": "string"},
    }
    data_fields = []
    for f in fields:
        a_type = avro_type_map.get(f["type"], "string")
        if not f.get("required", True):
            a_type = ["null", a_type] if not isinstance(a_type, list) else a_type
            data_fields.append({"name": f["name"], "type": a_type, "default": None})
        else:
            data_fields.append({"name": f["name"], "type": a_type})
    return {
        "type": "record",
        "name": f"{log_class}Envelope",
        "namespace": "structured_logging",
        "fields": [
            {"name": "_log_type", "type": "string"},
            {"name": "_log_class", "type": "string"},
            {"name": "_version", "type": "string"},
            {
                "name": "data",
                "type": {
                    "type": "record",
                    "name": f"{log_class}Data",
                    "fields": data_fields,
                },
            },
        ],
    }
