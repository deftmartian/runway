#!/usr/bin/env bash
set -euo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repository"

complexity_python="${RUNWAY_COMPLEXITY_PYTHON:-python3}"

"$complexity_python" -m lizard \
  -l kotlin \
  -l python \
  -C 10 \
  -w \
  -i 0 \
  -s cyclomatic_complexity \
  android/app/src \
  android/data/src \
  android/domain/src \
  android/ci
