#!/usr/bin/env python3
"""Per-column Parquet compression analysis.

Generates N synthetic records using the same generator as the bench,
writes them to Parquet (zstd) the same way the sidecar does, then uses
Parquet metadata to report per-column compressed/uncompressed bytes.

The point: dictionary-encoded low-cardinality columns (event_type,
device_type) compress to a tiny fraction of their wire size, while
high-cardinality (user_id) and free-text-ish (page_url, properties)
columns compress much less. This is the column-format multiplier on
top of zstd.
"""
import io
import json
import random
import statistics
import sys
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

# Make the bench driver/ importable
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "driver"))
import schema as S  # noqa: E402

N = int(sys.argv[1]) if len(sys.argv) > 1 else 100_000


def _build_arrow_table(records):
    cols = {f.name: [] for f in _SCHEMA}
    for r in records:
        cols["produced_at_ns"].append(r.get("produced_at_ns"))
        cols["timestamp"].append(r.get("timestamp"))
        cols["event_date"].append(r.get("event_date"))
        cols["user_id"].append(r.get("user_id"))
        cols["session_id"].append(r.get("session_id"))
        cols["event_type"].append(r.get("event_type"))
        cols["page_url"].append(r.get("page_url"))
        cols["device_type"].append(r.get("device_type"))
        cols["duration_ms"].append(r.get("duration_ms"))
        cols["properties_json"].append(json.dumps(r.get("properties") or {}, separators=(",", ":")))
    return pa.table(cols, schema=_SCHEMA)


_SCHEMA = pa.schema([
    pa.field("produced_at_ns", pa.int64()),
    pa.field("timestamp", pa.string()),
    pa.field("event_date", pa.string()),
    pa.field("user_id", pa.string()),
    pa.field("session_id", pa.string()),
    pa.field("event_type", pa.string()),
    pa.field("page_url", pa.string()),
    pa.field("device_type", pa.string()),
    pa.field("duration_ms", pa.int64()),
    pa.field("properties_json", pa.string()),
])


def _write_and_measure(records, *, compression, use_dictionary):
    table = _build_arrow_table(records)
    buf = io.BytesIO()
    pq.write_table(
        table, buf,
        compression=compression,
        compression_level=3 if compression == "zstd" else None,
        use_dictionary=use_dictionary,
    )
    body = buf.getvalue()

    # Parse Parquet footer for per-column byte counts
    pf = pq.ParquetFile(io.BytesIO(body))
    md = pf.metadata
    rows = []
    for rg in range(md.num_row_groups):
        rg_md = md.row_group(rg)
        for c in range(rg_md.num_columns):
            cc = rg_md.column(c)
            rows.append({
                "column": cc.path_in_schema,
                "uncompressed": cc.total_uncompressed_size,
                "compressed": cc.total_compressed_size,
                "encodings": [str(e) for e in cc.encodings],
                "compression": cc.compression,
            })
    return body, rows


def _cardinality(records, key):
    s = set()
    for r in records:
        v = r.get(key)
        if isinstance(v, dict):
            v = json.dumps(v, sort_keys=True)
        s.add(v)
    return len(s)


def _per_record_json_bytes(records, key):
    total = 0
    for r in records:
        if key == "properties_json":
            total += len(json.dumps(r.get("properties") or {}, separators=(",", ":")))
        else:
            v = r.get(key)
            total += len(str(v if v is not None else ""))
    return total


def main():
    rng = random.Random(1234)
    print(f"Generating {N} records...", file=sys.stderr)
    records = [S.make_record(rng) for _ in range(N)]
    print("Done. Writing parquet variants...", file=sys.stderr)

    # Three variants: no compression (just dictionary), snappy+dict, zstd+dict.
    # Plus zstd WITHOUT dictionary to show what the column format buys.
    variants = [
        ("uncompressed+dict", "none", True),
        ("snappy+dict", "snappy", True),
        ("zstd+dict", "zstd", True),
        ("zstd-no-dict", "zstd", False),
    ]

    raw_jsonl_bytes = sum(
        len(json.dumps(r, separators=(",", ":")).encode("utf-8")) + 1
        for r in records
    )

    print()
    print(f"=== {N:,} records — raw JSONL bytes: {raw_jsonl_bytes:,} ({raw_jsonl_bytes / N:.0f} B/record) ===")
    print()

    cards = {
        "produced_at_ns": _cardinality(records, "produced_at_ns"),
        "timestamp": _cardinality(records, "timestamp"),
        "event_date": _cardinality(records, "event_date"),
        "user_id": _cardinality(records, "user_id"),
        "session_id": _cardinality(records, "session_id"),
        "event_type": _cardinality(records, "event_type"),
        "page_url": _cardinality(records, "page_url"),
        "device_type": _cardinality(records, "device_type"),
        "duration_ms": _cardinality(records, "duration_ms"),
        "properties_json": _cardinality(records, "properties"),
    }
    raw_field_bytes = {k: _per_record_json_bytes(records, k) for k in cards}

    print(f"{'column':<18} {'cardinality':>12} {'raw_jsonl_B':>14} {'B/record':>10}")
    for k, c in cards.items():
        rb = raw_field_bytes[k]
        print(f"  {k:<16} {c:>12,} {rb:>14,} {rb/N:>10.1f}")
    print()

    print(f"=== whole-file size by compression variant ===")
    print(f"{'variant':<22} {'bytes':>14} {'vs_raw_jsonl':>14}")
    print(f"  {'raw_jsonl':<20} {raw_jsonl_bytes:>14,} {1.000:>14.3f}")
    bodies = {}
    for name, comp, dict_on in variants:
        body, _ = _write_and_measure(records, compression=comp, use_dictionary=dict_on)
        bodies[name] = body
        print(f"  {name:<20} {len(body):>14,} {len(body)/raw_jsonl_bytes:>14.3f}")
    print()

    print(f"=== per-column compressed bytes (zstd+dict) ===")
    _, col_rows = _write_and_measure(records, compression="zstd", use_dictionary=True)
    print(f"{'column':<18} {'uncompressed':>14} {'compressed':>14} {'ratio':>8} {'B/record':>10}")
    total_unc = 0
    total_cmp = 0
    for r in col_rows:
        unc = r["uncompressed"]
        cmp_ = r["compressed"]
        total_unc += unc
        total_cmp += cmp_
        print(f"  {r['column']:<16} {unc:>14,} {cmp_:>14,} {cmp_/max(unc,1):>8.3f} {cmp_/N:>10.2f}")
    print(f"  {'TOTAL':<16} {total_unc:>14,} {total_cmp:>14,} {total_cmp/max(total_unc,1):>8.3f} {total_cmp/N:>10.2f}")

    # Same table for zstd WITHOUT dictionary, so we can see the dictionary effect
    print()
    print(f"=== per-column compressed bytes (zstd, NO dictionary encoding) ===")
    _, col_rows_nd = _write_and_measure(records, compression="zstd", use_dictionary=False)
    print(f"{'column':<18} {'uncompressed':>14} {'compressed':>14} {'ratio':>8} {'B/record':>10}")
    total_unc_nd = 0
    total_cmp_nd = 0
    for r in col_rows_nd:
        unc = r["uncompressed"]
        cmp_ = r["compressed"]
        total_unc_nd += unc
        total_cmp_nd += cmp_
        print(f"  {r['column']:<16} {unc:>14,} {cmp_:>14,} {cmp_/max(unc,1):>8.3f} {cmp_/N:>10.2f}")
    print(f"  {'TOTAL':<16} {total_unc_nd:>14,} {total_cmp_nd:>14,} {total_cmp_nd/max(total_unc_nd,1):>8.3f} {total_cmp_nd/N:>10.2f}")

    # Save JSON for the report
    out = {
        "n_records": N,
        "raw_jsonl_bytes": raw_jsonl_bytes,
        "cardinality_per_column": cards,
        "raw_jsonl_bytes_per_column": raw_field_bytes,
        "whole_file_bytes": {name: len(body) for name, body in bodies.items()},
        "per_column_zstd_dict": col_rows,
        "per_column_zstd_no_dict": col_rows_nd,
    }
    out_path = ROOT / "results" / "parquet_column_analysis.json"
    out_path.write_text(json.dumps(out, indent=2, default=str))
    print(f"\nwrote {out_path}")


if __name__ == "__main__":
    main()
