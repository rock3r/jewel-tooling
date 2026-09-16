#!/usr/bin/env python3
"""Verify the current version's unique production ZIP and write its SHA-256 file."""
import argparse
import importlib.util
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--tag")
args = parser.parse_args()
properties = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text().splitlines() if "=" in line and not line.startswith("#"))
version = properties["pluginVersion"]
if args.tag and args.tag != "v" + version:
    raise SystemExit(f"Tag {args.tag} does not match pluginVersion={version}")
expected = root / "build/distributions" / ("jewel-tooling-" + version + ".zip")
if list(expected.parent.glob("*.zip")) != [expected]:
    raise SystemExit("Expected only the current production ZIP; remove old build/distributions output")
spec = importlib.util.spec_from_file_location("verify", root / "scripts/verify-artifacts.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
expected.with_suffix(".zip.sha256").write_text(verify.verify_zip(expected) + "  " + expected.name + "\n")
print(expected.name)
