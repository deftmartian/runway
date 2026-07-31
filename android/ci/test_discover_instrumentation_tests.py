#!/usr/bin/env python3
"""Regression tests for bounded Android instrumentation discovery."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


sys.dont_write_bytecode = True
MODULE_PATH = Path(__file__).with_name("discover-instrumentation-tests.py")
SPEC = importlib.util.spec_from_file_location("instrumentation_discovery", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
DISCOVERY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DISCOVERY)


class InstrumentationDiscoveryTest(unittest.TestCase):
    def test_returns_a_matching_kotlin_test_class(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            source_root = Path(temporary_directory)
            self.write_source(
                source_root / "example" / "ExampleTest.kt",
                "package example\n\ninternal class ExampleTest\n",
            )

            self.assertEqual(["example.ExampleTest"], DISCOVERY.discover(source_root))

    def test_rejects_a_kotlin_source_the_filter_would_skip(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            source_root = Path(temporary_directory)
            self.write_source(
                source_root / "example" / "ExampleSpec.kt",
                "package example\n\nclass ExampleSpec\n",
            )

            with self.assertRaisesRegex(
                DISCOVERY.DiscoveryError,
                r"must use the \*Test\.kt convention",
            ):
                DISCOVERY.discover(source_root)

    def test_rejects_java_instead_of_silently_omitting_it(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            source_root = Path(temporary_directory)
            self.write_source(
                source_root / "example" / "ExampleTest.java",
                "package example;\n\npublic class ExampleTest {}\n",
            )

            with self.assertRaisesRegex(DISCOVERY.DiscoveryError, "Kotlin sources only"):
                DISCOVERY.discover(source_root)

    def test_rejects_an_additional_top_level_class_the_filter_would_skip(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            source_root = Path(temporary_directory)
            self.write_source(
                source_root / "example" / "ExampleTest.kt",
                "package example\n\nclass ExampleTest\nclass HiddenTest\n",
            )

            with self.assertRaisesRegex(
                DISCOVERY.DiscoveryError,
                "exactly one matching top-level class",
            ):
                DISCOVERY.discover(source_root)

    @staticmethod
    def write_source(path: Path, text: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
