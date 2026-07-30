#!/usr/bin/env python3
"""Run one command with a portable wall-clock timeout."""

from __future__ import annotations

import shlex
import subprocess
import sys


def main() -> int:
    if len(sys.argv) < 3:
        print(
            f"usage: {sys.argv[0]} <seconds> <command> [args...]",
            file=sys.stderr,
        )
        return 2

    try:
        seconds = int(sys.argv[1])
    except ValueError:
        print("timeout must be a positive number of seconds", file=sys.stderr)
        return 2
    if seconds <= 0:
        print("timeout must be a positive number of seconds", file=sys.stderr)
        return 2

    command = sys.argv[2:]
    try:
        result = subprocess.run(command, timeout=seconds, check=False)
    except FileNotFoundError:
        print(f"command not found: {command[0]}", file=sys.stderr)
        return 127
    except subprocess.TimeoutExpired:
        print(
            f"timed out after {seconds}s: {shlex.join(command)}",
            file=sys.stderr,
        )
        return 124

    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
