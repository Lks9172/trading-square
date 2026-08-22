#!/usr/bin/env bash
set -euo pipefail

container="${1:?usage: sample_docker_resources.sh CONTAINER [SAMPLES] [INTERVAL_SECONDS]}"
samples="${2:-30}"
interval="${3:-1}"

printf 'captured_at,name,cpu_percent,memory_usage,memory_percent,block_io,net_io,pids\n'
for ((index = 0; index < samples; index += 1)); do
  captured_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  docker stats --no-stream \
    --format "${captured_at},{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.BlockIO}},{{.NetIO}},{{.PIDs}}" \
    "${container}"
  sleep "${interval}"
done
