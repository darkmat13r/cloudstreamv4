#!/usr/bin/env python3
"""Fails if any test result on disk records a failure, whatever Gradle's exit code said.

A Kotlin Multiplatform test task runs with its failures deferred to the aggregate report, so a failed `jvmTest` is
cacheable, and restoring it from the build cache left `check` at 0 (INF-10). The restored XML still says so, so read that.

Run from the root of a tree after a test run. A result left by an older run in the same tree also counts: rerun that task.
"""
import glob
import sys
import xml.etree.ElementTree as ElementTree

failed = 0
read = 0
for path in glob.glob("**/build/test-results/**/TEST-*.xml", recursive=True) + glob.glob(
    "**/build/outputs/androidTest-results/**/TEST-*.xml", recursive=True
):
    root = ElementTree.parse(path).getroot()
    read += 1
    count = int(root.get("failures", "0")) + int(root.get("errors", "0"))
    if count:
        failed += count
        print(f"FAILED {count}: {path}")
print(f"test results read: {read}, failures: {failed}")
sys.exit(1 if failed or not read else 0)
