#!/usr/bin/env python3
"""Legt die Macrobenchmark-Zahlen von „vorher“ und „nachher“ nebeneinander (Markdown). Erfindet nichts: fehlt ein Wert, steht „–“."""
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "ab-results")
values = defaultdict(lambda: defaultdict(list))  # (benchmark, metrik) -> variante -> [werte je Runde]
status = {}

for run_dir in sorted(p for p in root.iterdir() if p.is_dir()) if root.exists() else []:
    variant = run_dir.name.rsplit("-", 1)[0]
    code = (run_dir / "exit-code").read_text().strip() if (run_dir / "exit-code").exists() else "?"
    status[run_dir.name] = code
    for path in run_dir.rglob("*.json"):
        try:
            data = json.loads(path.read_text())
        except (OSError, ValueError):
            continue
        for bench in data.get("benchmarks", []):
            name = bench.get("name", "?")
            for metric, stats in bench.get("metrics", {}).items():
                if isinstance(stats, dict) and "median" in stats:
                    values[(name, metric + " (Median)")][variant].append(stats["median"])
            for metric, stats in bench.get("sampledMetrics", {}).items():
                for key in ("P50", "P95"):
                    if isinstance(stats, dict) and key in stats:
                        values[(name, f"{metric} ({key})")][variant].append(stats[key])


def cell(numbers):
    if not numbers:
        return "–"
    spread = f" [{min(numbers):.1f}–{max(numbers):.1f}]" if len(numbers) > 1 else ""
    return f"{statistics.median(numbers):.1f}{spread}"


print("## Messung vorher / nachher (Emulator API 34, abwechselnd auf demselben Emulator)\n")
print("Läufe: " + ", ".join(f"{name}: Gradle-Ausgang {code}" for name, code in status.items()) + "\n")
for run_dir in sorted(p for p in root.iterdir() if p.is_dir()) if root.exists() else []:
    log = run_dir / "gradle.log"
    if status.get(run_dir.name) not in (None, "0") and log.exists():
        lines = [l for l in log.read_text(errors="replace").splitlines() if any(k in l for k in ("FAILED", "Exception", "AssertionError", "error:", "No tests"))]
        print(f"**{run_dir.name}: Fehlerauszug**\n```\n" + "\n".join(lines[:15]) + "\n```\n")
if not values:
    print("**Keine Zahlen.** Kein Benchmark hat ein Ergebnis geschrieben (siehe gradle.log je Lauf im Artefakt). Es wird nichts geschätzt.")
    sys.exit(0)
print("Zelle = Median über die Runden [kleinster–größter Rundenwert]. Eine Rundenzahl von 1 sagt nichts über die Streuung.\n")
print("| Benchmark | Metrik | vorher | nachher | Änderung |")
print("|---|---|---|---|---|")
for (bench, metric), by_variant in sorted(values.items()):
    before, after = by_variant.get("vorher", []), by_variant.get("nachher", [])
    change = "–"
    if before and after and statistics.median(before) != 0:
        change = f"{(statistics.median(after) / statistics.median(before) - 1) * 100:+.1f} %"
    print(f"| {bench} | {metric} | {cell(before)} | {cell(after)} | {change} |")
