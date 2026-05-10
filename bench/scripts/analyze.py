#!/usr/bin/env python3
"""Aggregate per-approach results into a comparison report."""
import csv
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "results"

# AWS list-price assumptions used for cost extrapolation. Conservative.
PRICES = {
    "msk_broker_hour_kafka_m_large": 0.21,   # kafka.m5.large MSK provisioned
    "msk_storage_gb_month": 0.10,            # MSK provisioned storage
    "s3_storage_gb_month": 0.023,            # standard
    "s3_put_per_1000": 0.005,                # PUT cost
    "s3_get_per_1000": 0.0004,               # GET cost
    "ec2_vcpu_hour_m6i": 0.024,              # m6i ~ $0.0966/hr 4 vCPU = $0.024/vCPU
    "data_transfer_gb": 0.00,                # within-AZ free
    "warpstream_per_gb": 0.20,               # WarpStream BYOC pricing approx ($0.20/GB ingested)
}


def load_approach(name: str) -> dict:
    d = RESULTS / name
    summary_path = d / "summary.json"
    out = {"approach": name, "ok": False}
    if not summary_path.exists():
        out["error"] = "no summary.json"
        return out
    out["summary"] = json.loads(summary_path.read_text())
    out["ok"] = True

    # Docker stats: average and peak CPU/mem per container
    stats_path = d / "docker-stats.csv"
    by_container = {}
    if stats_path.exists():
        with open(stats_path) as f:
            r = csv.DictReader(f)
            for row in r:
                cname = row["name"]
                rec = by_container.setdefault(cname, {"cpu": [], "mem": [], "net_in": [], "net_out": [], "blk_in": [], "blk_out": []})
                rec["cpu"].append(float(row["cpu_pct"]))
                rec["mem"].append(float(row["mem_mib"]))
                rec["net_in"].append(float(row["net_in_mib"]))
                rec["net_out"].append(float(row["net_out_mib"]))
                rec["blk_in"].append(float(row["blk_in_mib"]))
                rec["blk_out"].append(float(row["blk_out_mib"]))
    cont_rollup = {}
    for ckey, rec in by_container.items():
        cname = ckey
        # For network and block I/O the values are CUMULATIVE since container start,
        # so the delta = max - min reflects what flowed during the window.
        n = len(rec["cpu"])
        cont_rollup[cname] = {
            "samples": n,
            "cpu_avg_pct": round(sum(rec["cpu"]) / n, 2) if n else 0,
            "cpu_max_pct": round(max(rec["cpu"]), 2) if n else 0,
            "mem_avg_mib": round(sum(rec["mem"]) / n, 1) if n else 0,
            "mem_max_mib": round(max(rec["mem"]), 1) if n else 0,
            "net_in_mib": round(max(rec["net_in"]) - min(rec["net_in"]), 1) if n else 0,
            "net_out_mib": round(max(rec["net_out"]) - min(rec["net_out"]), 1) if n else 0,
            "blk_out_mib": round(max(rec["blk_out"]) - min(rec["blk_out"]), 1) if n else 0,
        }
    out["containers"] = cont_rollup

    # MinIO stats from sidecar log (more reliable than `mc du`).
    # Sidecar emits one PUT line per object with raw_bytes/object_bytes/records.
    sidecar_log = d / "logs" / f"bench-sidecar-{name}.log"
    if sidecar_log.exists():
        puts = 0
        raw_total = 0
        obj_total = 0
        rec_total = 0
        for line in sidecar_log.read_text().splitlines():
            if "] PUT " not in line:
                continue
            puts += 1
            for tok in line.split():
                if tok.startswith("raw_bytes="):
                    raw_total += int(tok.split("=", 1)[1])
                elif tok.startswith("object_bytes="):
                    obj_total += int(tok.split("=", 1)[1])
                elif tok.startswith("records="):
                    rec_total += int(tok.split("=", 1)[1])
        if puts:
            out["minio_object_count"] = puts
            out["minio_bytes_mib"] = round(obj_total / 1024 / 1024, 2)
            out["minio_raw_input_mib"] = round(raw_total / 1024 / 1024, 2)
            out["minio_compression_ratio"] = round(obj_total / max(raw_total, 1), 4)
            out["minio_records_uploaded"] = rec_total
    return out


def cost_estimate(a: dict) -> dict:
    """Extrapolate per-million-records cost to AWS list prices.
    Assumes 1hr steady-state from the measured metrics."""
    s = a.get("summary", {})
    n = s.get("records_after_warmup", 0)
    if not n:
        return {}
    duration = s.get("duration_s_observed", 1)
    rps = n / max(duration, 1)
    per_hour = rps * 3600
    per_million = 1_000_000 / max(per_hour, 1)  # hours of operation per 1M rows

    name = a["approach"]
    conts = a.get("containers", {})
    minio_bytes_mib = a.get("minio_bytes_mib", 0)
    obj_count = a.get("minio_object_count") or 0

    # Compute aggregate CPU-hours during the window (CPU% / 100 * duration_h).
    cpu_h = 0.0
    for cname, c in conts.items():
        cpu_h += (c["cpu_avg_pct"] / 100.0) * (duration / 3600.0)
    cpu_per_million_h = cpu_h * (1_000_000 / max(n, 1))

    # Network bytes egressed: sum net_out across producer-side container only.
    net_out_mib_total = sum(c["net_out_mib"] for c in conts.values())
    net_gb_per_million = (net_out_mib_total / 1024.0) * (1_000_000 / max(n, 1))

    out = {
        "rps_observed": round(rps, 1),
        "records_per_hour_extrap": int(per_hour),
        "cpu_hours_per_1M_rows": round(cpu_per_million_h, 4),
        "ec2_cost_per_1M_rows": round(cpu_per_million_h * PRICES["ec2_vcpu_hour_m6i"], 5),
        "net_egress_gb_per_1M_rows": round(net_gb_per_million, 3),
    }

    if name in ("A", "B"):
        # Kafka transport: 3-broker MSK m5.large, 24/7. Amortise over hourly throughput.
        msk_per_hour = 3 * PRICES["msk_broker_hour_kafka_m_large"]
        out["msk_brokers_per_hr"] = round(msk_per_hour, 4)
        out["msk_cost_per_1M_rows"] = round(msk_per_hour / max(per_hour / 1_000_000, 0.0001), 4)
        out["transport_cost_per_1M_rows"] = out["msk_cost_per_1M_rows"]
    elif name in ("C", "D"):
        # Sidecar -> S3: per-PUT + storage
        puts_per_million = obj_count * (1_000_000 / max(n, 1))
        out["s3_puts_per_1M_rows"] = round(puts_per_million, 1)
        out["s3_put_cost_per_1M_rows"] = round(puts_per_million / 1000.0 * PRICES["s3_put_per_1000"], 5)
        gb_per_million = (minio_bytes_mib / 1024.0) * (1_000_000 / max(n, 1))
        out["s3_storage_gb_per_1M_rows"] = round(gb_per_million, 4)
        out["s3_storage_cost_per_1M_rows_per_month"] = round(gb_per_million * PRICES["s3_storage_gb_month"], 5)
        out["transport_cost_per_1M_rows"] = (out["s3_put_cost_per_1M_rows"]
                                             + out["s3_storage_cost_per_1M_rows_per_month"])
    elif name == "E":
        # WarpStream BYOC: estimate from agent CPU + ingest volume at vendor list price.
        # Vendor publishes ~$0.20 per GB ingested. Use the wire bytes received by the agent
        # as a proxy for ingest GB. Agent CPU runs as ordinary EC2.
        # For comparison-fairness we also report the equivalent fixed-MSK cost.
        wire_gb_per_million = (s.get("bytes_after_warmup", 0) / 1024 / 1024 / 1024) \
                              * (1_000_000 / max(n, 1))
        out["wire_gb_per_1M_rows"] = round(wire_gb_per_million, 4)
        out["warpstream_ingest_cost_per_1M_rows"] = round(wire_gb_per_million * PRICES["warpstream_per_gb"], 5)
        # Agent footprint amortised — using S3 storage for the data (zstd batches).
        # Agent net_out estimates bytes written to S3.
        agent_cont = conts.get("bench-warpstream", {})
        s3_bytes_mib = agent_cont.get("net_out_mib", 0)
        gb_per_million = (s3_bytes_mib / 1024.0) * (1_000_000 / max(n, 1))
        out["s3_storage_gb_per_1M_rows"] = round(gb_per_million, 4)
        out["s3_storage_cost_per_1M_rows_per_month"] = round(gb_per_million * PRICES["s3_storage_gb_month"], 5)
        out["transport_cost_per_1M_rows"] = (out["warpstream_ingest_cost_per_1M_rows"]
                                             + out["s3_storage_cost_per_1M_rows_per_month"])

    out["total_cost_per_1M_rows"] = round(out["ec2_cost_per_1M_rows"] + out["transport_cost_per_1M_rows"], 5)
    return out


def main():
    rows = []
    for name in ["A", "B", "C", "D", "E"]:
        rows.append(load_approach(name))
    summary = {a["approach"]: a for a in rows}
    cost = {a["approach"]: cost_estimate(a) for a in rows if a.get("ok")}

    out = {"approaches": summary, "cost_extrapolation": cost, "prices_used": PRICES}
    out_path = RESULTS / "comparison.json"
    out_path.write_text(json.dumps(out, indent=2, default=str))
    print(f"wrote {out_path}")

    # Print compact comparison table
    print("\n=== latency (ms) ===")
    print(f"{'approach':<10} {'p50':>8} {'p95':>8} {'p99':>8} {'p999':>8} {'max':>8} {'records':>10}")
    for a in rows:
        if not a.get("ok"):
            print(f"{a['approach']:<10} ERROR: {a.get('error')}")
            continue
        s = a["summary"]
        print(f"{a['approach']:<10} {s['p50_ms']:>8.2f} {s['p95_ms']:>8.2f} "
              f"{s['p99_ms']:>8.2f} {s['p999_ms']:>8.2f} {s['max_ms']:>8.2f} {s['records_after_warmup']:>10d}")

    print("\n=== resources (avg CPU%, peak MiB, network MiB egressed during run) ===")
    for a in rows:
        if not a.get("ok"):
            continue
        print(f"\n  Approach {a['approach']}:")
        for cname, c in a["containers"].items():
            print(f"    {cname:<28} cpu={c['cpu_avg_pct']:>6.1f}% mem={c['mem_max_mib']:>7.1f}MiB "
                  f"net_in={c['net_in_mib']:>7.1f}MiB net_out={c['net_out_mib']:>7.1f}MiB")
        if a.get("minio_bytes_mib") is not None:
            print(f"    MinIO bucket: {a.get('minio_bytes_mib','?')} MiB across "
                  f"{a.get('minio_object_count','?')} objects")

    print("\n=== cost extrapolation per 1M rows (AWS list, conservative) ===")
    print(f"{'approach':<10} {'rps':>8} {'cpu_h':>8} {'EC2 $':>8} {'transp $':>10} {'total $':>9}")
    for a in rows:
        c = cost.get(a["approach"])
        if not c:
            continue
        print(f"{a['approach']:<10} {c['rps_observed']:>8.1f} "
              f"{c['cpu_hours_per_1M_rows']:>8.4f} {c['ec2_cost_per_1M_rows']:>8.5f} "
              f"{c['transport_cost_per_1M_rows']:>10.5f} {c['total_cost_per_1M_rows']:>9.5f}")


if __name__ == "__main__":
    main()
