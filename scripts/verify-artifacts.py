#!/usr/bin/env python3
"""Validate distribution contents and recorded screenshot provenance."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile


def verify_zip(path):
    with zipfile.ZipFile(path) as archive:
        files = [name for name in archive.namelist() if not name.endswith("/")]
        if len(files) != 1 or not re.fullmatch(r"jewel-tooling/lib/jewel-tooling-[0-9][^/]*\.jar", files[0]):
            raise ValueError(f"Unexpected distribution entries: {files}")
        with zipfile.ZipFile(io.BytesIO(archive.read(files[0]))) as jar:
            names = [name for name in jar.namelist() if not name.endswith("/")]
            required = {"META-INF/plugin.xml", "messages/JewelToolingBundle.properties"}
            if not required.issubset(names):
                raise ValueError("Plugin descriptor or resource bundle missing")
            for name in names:
                allowed = (
                    name in required
                    or name in {"META-INF/MANIFEST.MF", "META-INF/dev.sebastiano.jewel.tooling_jewel-tooling.kotlin_module", "META-INF/LICENSE", "META-INF/NOTICE"}
                    or name.startswith("inlayProviders/jewel.compose.stability/")
                    or (name.startswith("dev/sebastiano/jewel/tooling/") and name.endswith(".class")
                        and not any(part in name for part in ("/e2e/", "Test", "Fixture", "Scenario")))
                )
                if not allowed:
                    raise ValueError(f"Unexpected production JAR entry: {name}")
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def source_digest(root, inputs):
    if inputs != sorted(set(inputs)) or not inputs:
        raise ValueError("Manifest input paths must be unique, sorted and nonempty")
    digest = hashlib.sha256()
    for name in inputs:
        path = root / name
        if Path(name).is_absolute() or ".." in Path(name).parts or not path.is_file():
            raise ValueError(f"Invalid capture input: {name}")
        data = path.read_bytes()
        digest.update(name.encode() + b"\0" + str(len(data)).encode() + b"\0" + data)
    return digest.hexdigest()


def capture_inputs(root):
    files = set()
    directories = ["src/main", "e2e/driver-plugin/src", "e2e/runner/src", "fixtures/standalone/src", "fixtures/ijpl/src"]
    for directory in directories:
        for path in (root / directory).rglob("*"):
            if path.is_file():
                files.add(path.relative_to(root).as_posix())
    files.update([
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "config/detekt.yml", "gradle/libs.versions.toml",
        "e2e/driver-plugin/build.gradle.kts", "e2e/runner/build.gradle.kts",
        "fixtures/standalone/build.gradle.kts", "fixtures/standalone/settings.gradle.kts",
        "fixtures/ijpl/.bazelrc", "fixtures/ijpl/MODULE.bazel.lock", "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar",
        "fixtures/ijpl/MODULE.bazel", "fixtures/ijpl/BUILD.bazel", "fixtures/ijpl/kotlin.bzl", "fixtures/ijpl/.bazelversion",
        "scripts/capture-retina.sh", "scripts/promote-captures.py", "scripts/prepare-bazel-fixture.py", "scripts/verify-artifacts.py",
    ])
    return sorted(files)


def verify_images(root):
    manifest = json.loads((root / "docs/images/manifest.json").read_text())
    if manifest["schemaVersion"] != 1:
        raise ValueError("Unsupported screenshot manifest version")
    if manifest["inputs"] != capture_inputs(root):
        raise ValueError("Capture input inventory changed; regenerate assets")
    if source_digest(root, manifest["inputs"]) != manifest["sourceSha256"]:
        raise ValueError("Capture inputs changed; regenerate Retina assets in this change")
    expected = {"standalone-editor", "standalone-ui", "ijpl-editor", "ijpl-ui", "explanation", "details-dark", "details-light"}
    if len(manifest["images"]) != len(expected) or {entry["scenario"] for entry in manifest["images"]} != expected:
        raise ValueError("Missing or unexpected documentation scenario")
    for entry in manifest["images"]:
        relative = Path(entry["path"])
        if relative.is_absolute() or ".." in relative.parts or relative.parts[:2] != ("docs", "images"):
            raise ValueError("Image must be inside docs/images")
        data = (root / relative).read_bytes()
        if data[:8] != b"\x89PNG\r\n\x1a\n":
            raise ValueError("Expected a PNG screenshot")
        width, height = struct.unpack(">II", data[16:24])
        if entry["scaleX"] != 2 or entry["scaleY"] != 2:
            raise ValueError("Documentation capture must use a real 2x device")
        if (width, height) != (entry["logicalWidth"] * 2, entry["logicalHeight"] * 2):
            raise ValueError("Native pixel dimensions do not match recorded device scale")
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError("Screenshot hash mismatch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zip", type=Path)
    parser.add_argument("--images", action="store_true")
    options = parser.parse_args()
    if not options.zip and not options.images:
        parser.error("Select --zip and/or --images")
    if options.zip:
        print(f"{verify_zip(options.zip)}  {options.zip.name}")
    if options.images:
        verify_images(Path(__file__).resolve().parents[1])
        print("Screenshot hashes, dimensions and recorded source provenance verified")


if __name__ == "__main__":
    main()
