#!/usr/bin/env bash
# Runs several benchmarks back to back: suite.sh <label>=<DH_CONFIG or empty> ...
# Example: suite.sh base= no-ssao=client.advanced.graphics.ssao.enableSsao=false
# Extra env (e.g. WIDTH/HEIGHT) is passed through to every run.

set -uo pipefail

BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
for run in "$@"; do
	label="${run%%=*}"
	DH_CONFIG="${run#*=}" "$BENCH_DIR/bench.sh" "$label"
done
