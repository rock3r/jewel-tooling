#!/usr/bin/env python3
"""Promote only successful native 2x captures from one fresh capture run."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import struct

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("verify", ROOT / "scripts/verify-artifacts.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture-id", required=True)
    parser.add_argument("--begin", action="store_true")
    args = parser.parse_args()
    if not args.capture_id or args.capture_id == "development" or not all(c.isalnum() or c == "-" for c in args.capture_id):
        parser.error("Use a fresh UUID capture ID")
    inputs = verify.capture_inputs(ROOT)
    digest = verify.source_digest(ROOT, inputs)
    snapshot = ROOT / ".local" / ("capture-" + args.capture_id + ".json")
    if args.begin:
        snapshot.parent.mkdir(exist_ok=True)
        if snapshot.exists():
            raise ValueError("Capture ID already used")
        snapshot.write_text(json.dumps({"inputs": inputs, "sourceSha256": digest}))
        return
    initial = json.loads(snapshot.read_text())
    if initial != {"inputs": inputs, "sourceSha256": digest}:
        raise ValueError("Capture sources changed during this run; recapture")
    selected = {}
    launches = {}
    mcp = {}
    for folder in (ROOT / "e2e/runner/build/artifacts").iterdir():
        mcp_metadata = folder / "mcp-capture.json"
        if mcp_metadata.is_file():
            capture = json.loads(mcp_metadata.read_text())
            if capture.get("captureId") == args.capture_id:
                for evidence in ("result.txt", "mcp-evidence.txt", "mcp-client-install-evidence.txt"):
                    if not (folder / evidence).read_text().startswith("PASS:"):
                        raise ValueError("Cannot promote a failed MCP scenario")
                target = (folder / "mcp-target.txt").read_text()
                if target in mcp:
                    raise ValueError("Ambiguous duplicate MCP capture for " + target)
                mcp[target] = folder
        launch_metadata = folder / "one-click-live-capture.json"
        if launch_metadata.is_file():
            launch = json.loads(launch_metadata.read_text())
            if launch.get("captureId") == args.capture_id:
                if not (folder / "result.txt").read_text().startswith("PASS:"):
                    raise ValueError("Cannot promote a failed launch scenario")
                target = (folder / "one-click-target.txt").read_text()
                if target in launches:
                    raise ValueError("Ambiguous duplicate launch capture for " + target)
                launches[target] = folder
        metadata = folder / "capture.json"
        if not metadata.is_file():
            continue
        capture = json.loads(metadata.read_text())
        if capture.get("captureId") != args.capture_id:
            continue
        if not (folder / "result.txt").is_file() or not (folder / "scenario.txt").is_file():
            raise ValueError("Incomplete or timed-out capture scenario: " + folder.name)
        if not (folder / "result.txt").read_text().startswith("PASS:"):
            raise ValueError("Cannot promote a failed scenario")
        target = (folder / "scenario.txt").read_text()
        if target in selected:
            raise ValueError("Ambiguous duplicate capture for " + target)
        selected[target] = folder
    if set(selected) != {"standalone", "ijpl"}:
        raise ValueError("Both target editor scenarios must pass in this capture run")
    if set(launches) != {"standalone", "ijpl"}:
        raise ValueError("Both production launch scenarios must pass in this capture run")
    if set(mcp) != {"standalone", "ijpl"}:
        raise ValueError("Both production MCP scenarios must pass in this capture run")
    standalone = ROOT / "fixtures/standalone/build/capture"
    captures = [
        ("mcp-standalone", mcp["standalone"], "mcp-setup.png", "mcp-capture.json"),
        ("mcp-ijpl", mcp["ijpl"], "mcp-setup.png", "mcp-capture.json"),
        ("standalone-editor", selected["standalone"], "editor-before.png", "capture.json"),
        ("explanation", selected["standalone"], "explanation.png", "capture.json"),
        ("details-dark", selected["standalone"], "details-dark.png", "details-dark-capture.json"),
        ("details-light", selected["standalone"], "details-light.png", "details-light-capture.json"),
        ("ijpl-editor", selected["ijpl"], "editor-before.png", "capture.json"),
        ("ijpl-ui", selected["ijpl"], "ijpl-ui.png", "ijpl-capture.json"),
        ("standalone-ui", standalone, "standalone-ui.png", "capture.json"),
        ("recording-standalone", selected["standalone"], "recording.png", "recording-capture.json"),
        ("recording-ijpl", selected["ijpl"], "recording.png", "recording-capture.json"),
        ("live-standalone", launches["standalone"], "one-click-live.png", "one-click-live-capture.json"),
        ("live-ijpl", launches["ijpl"], "one-click-live.png", "one-click-live-capture.json"),
    ]
    images = []
    pending = []
    destination = ROOT / "user-guide/images"
    destination.mkdir(parents=True, exist_ok=True)
    for scenario, folder, filename, metadata in captures:
        entry = json.loads((folder / metadata).read_text())
        if entry.get("captureId") != args.capture_id or entry["scaleX"] != 2 or entry["scaleY"] != 2:
            raise ValueError("Capture must come from this run on a real 2x device")
        data = (folder / filename).read_bytes()
        if struct.unpack(">II", data[16:24]) != (entry["logicalWidth"] * 2, entry["logicalHeight"] * 2):
            raise ValueError("Screenshot is not at native 2x dimensions")
        target = destination / (scenario + ".png")
        pending.append((folder / filename, target))
        images.append({"scenario": scenario, "path": target.relative_to(ROOT).as_posix(), "logicalWidth": entry["logicalWidth"], "logicalHeight": entry["logicalHeight"], "scaleX": 2, "scaleY": 2, "sha256": hashlib.sha256(data).hexdigest()})
    for source, target in pending:
        shutil.copyfile(source, target)
    (destination / "manifest.json").write_text(json.dumps({"schemaVersion": 1, "captureId": args.capture_id, "inputs": inputs, "sourceSha256": digest, "images": images}, indent=2) + "\n")
    verify.verify_images(ROOT)
    print("Promoted thirteen verified native Retina captures")


if __name__ == "__main__":
    main()
