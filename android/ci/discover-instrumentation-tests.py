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


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1] not in MODULES:
        print(f"usage: {sys.argv[0]} <{'|'.join(sorted(MODULES))}>", file=sys.stderr)
        return 2

    repository = Path(__file__).resolve().parents[2]
    source_root = repository / "android" / sys.argv[1] / "src" / "androidTest"
    test_classes: list[str] = []
    for source in sorted(source_root.rglob("*Test.kt")):
        text = source.read_text(encoding="utf-8")
        package_match = PACKAGE_PATTERN.search(text)
        if package_match is None:
            print(f"instrumented test has no package declaration: {source}", file=sys.stderr)
            return 1

        class_pattern = re.compile(
            rf"^(?:(?:public|internal)\s+)?class\s+{re.escape(source.stem)}\b",
            re.MULTILINE,
        )
        if class_pattern.search(text) is None:
            print(
                f"instrumented test class must match its file name: {source}",
                file=sys.stderr,
            )
            return 1
        test_classes.append(f"{package_match.group(1)}.{source.stem}")

    if not test_classes:
        print(f"no instrumented tests found under {source_root}", file=sys.stderr)
        return 1
    if len(test_classes) != len(set(test_classes)):
        print("instrumented test discovery produced duplicate class names", file=sys.stderr)
        return 1

    print(",".join(test_classes))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
