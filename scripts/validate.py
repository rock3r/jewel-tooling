#!/usr/bin/env python3
"""Run explicit local and CI validation profiles without publishing artifacts."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
KOTLIN_PROJECTS = (
    "", ":agent", ":recording", ":recording-compose", ":mcp-runtime",
    ":test-fixtures:compiler-metadata", ":e2e:driver-plugin", ":e2e:runner",
)
TEST_PROJECTS = ("", ":agent", ":recording", ":recording-compose", ":mcp-runtime", ":mcp-bootstrap")


def tasks(names):
    return [f"{project}:{name}" for project in KOTLIN_PROJECTS for name in names]


def commands(profile, *, windows=False, python=sys.executable, ide_path=None):
    gradle = "gradlew.bat" if windows else "./gradlew"
    py = lambda *args: [python, *args]
    if profile == "e2e":
        run = [gradle, ":e2e:runner:test", "--tests", "*JewelTargetsTest*"]
        if ide_path:
            run.append(f"-PtestIdePath={ide_path}")
        return [
            [gradle, ":e2e:driver-plugin:exportFixtureSdk"],
            py("scripts/prepare-bazel-fixture.py"),
            [gradle, "-p", "fixtures/standalone", ":test", ":ktfmtCheck", ":detekt"],
            py("scripts/verify-trace-markers.py", "fixtures/standalone/build/classes/kotlin/main"),
            run,
        ]
    if profile not in ("fast", "full"):
        raise ValueError(f"Unknown validation profile: {profile}")
    plan = [
        py("-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"),
        py("scripts/check-doc-links.py"),
        [gradle, *tasks(("ktfmtCheck", "detekt")), ":detektBuildScripts", ":detektComposeFixtures"],
        [gradle, "-p", "fixtures/standalone", ":ktfmtCheck", ":detekt"],
    ]
    if profile == "full":
        plan += [
            [gradle, *tasks(("detektMain", "detektTest")),
             *[f"{project}:test" for project in TEST_PROJECTS],
             ":exportCompilerFixtures", ":buildPlugin", ":verifyPluginStructure", ":verifyPlugin"],
            [gradle, "-p", "fixtures/standalone", ":detektMain", ":detektTest"],
            py("scripts/prepare-distribution.py"),
            py("scripts/verify-artifacts.py", "--images"),
        ]
    return plan


def execute(plan, runner=subprocess.run):
    for command in plan:
        print(json.dumps(command), flush=True)
        runner(command, cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=("fast", "full", "e2e"))
    parser.add_argument("--dry-run", action="store_true", help="Print the command plan without running tools")
    parser.add_argument("--ide-path", help="Use an existing IDE installation for E2E")
    args = parser.parse_args()
    if args.ide_path and args.profile != "e2e":
        parser.error("--ide-path applies only to e2e")
    plan = commands(args.profile, windows=os.name == "nt", ide_path=args.ide_path)
    if args.dry_run:
        print(json.dumps(plan, indent=2))
    else:
        execute(plan)


if __name__ == "__main__":
    main()
