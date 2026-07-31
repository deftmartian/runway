#!/usr/bin/env python3
"""List instrumented test classes without on-device classpath discovery."""

from __future__ import annotations

import re
import sys
from pathlib import Path


MODULES = {"app", "data"}
PACKAGE_PATTERN = re.compile(
    r"^package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\s*$",
    re.MULTILINE,
)
TOP_LEVEL_CLASS_PATTERN = re.compile(
    r"^(?:(?:public|internal|private|protected|abstract|open|final|sealed|data|"
    r"enum|value|annotation)\s+)*class\s+([A-Za-z_]\w*)\b",
    re.MULTILINE,
)


class DiscoveryError(ValueError):
    """The source tree cannot be represented by the bounded class filter."""


def discover(source_root: Path) -> list[str]:
    sources = sorted(
        source
        for source in source_root.rglob("*")
        if source.is_file() and source.suffix in {".java", ".kt"}
    )
    if not sources:
        raise DiscoveryError(f"no instrumented tests found under {source_root}")

    test_classes: list[str] = []
    for source in sources:
        if source.suffix != ".kt":
            raise DiscoveryError(
                f"instrumented test discovery supports Kotlin sources only: {source}",
            )
        if not source.name.endswith("Test.kt"):
            raise DiscoveryError(
                f"instrumented test source must use the *Test.kt convention: {source}",
            )

        text = source.read_text(encoding="utf-8")
        package_match = PACKAGE_PATTERN.search(text)
        if package_match is None:
            raise DiscoveryError(f"instrumented test has no package declaration: {source}")

        declared_classes = TOP_LEVEL_CLASS_PATTERN.findall(text)
        if declared_classes != [source.stem]:
            raise DiscoveryError(
                "instrumented test source must contain exactly one matching top-level class: "
                f"{source}",
            )
        test_classes.append(f"{package_match.group(1)}.{source.stem}")

    if len(test_classes) != len(set(test_classes)):
        raise DiscoveryError("instrumented test discovery produced duplicate class names")
    return test_classes


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1] not in MODULES:
        print(f"usage: {sys.argv[0]} <{'|'.join(sorted(MODULES))}>", file=sys.stderr)
        return 2

    repository = Path(__file__).resolve().parents[2]
    source_root = repository / "android" / sys.argv[1] / "src" / "androidTest"
    try:
        test_classes = discover(source_root)
    except DiscoveryError as error:
        print(error, file=sys.stderr)
        return 1

    print(",".join(test_classes))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
