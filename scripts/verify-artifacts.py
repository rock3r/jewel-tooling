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
        plugins = [name for name in files if re.fullmatch(r"jewel-tooling/lib/jewel-tooling-([0-9][^/]*)\.jar", name)]
        if len(plugins) != 1:
            raise ValueError("Expected one production plugin JAR")
        version = re.fullmatch(r"jewel-tooling/lib/jewel-tooling-([0-9][^/]*)\.jar", plugins[0]).group(1)
        shared = f"jewel-tooling/lib/recording-{version}.jar"
        bootstrap = "jewel-tooling/lib/mcp-bootstrap.jar"
        if len(files) != 3 or set(files) != {plugins[0], shared, bootstrap}:
            raise ValueError(f"Unexpected distribution entries: {files}")
        verify_plugin_jar(archive.read(plugins[0]), version)
        verify_recording_jar(archive.read(shared))
        verify_mcp_jar(archive.read(bootstrap), bootstrap=True)
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify_plugin_jar(data, version):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        names = [name for name in jar.namelist() if not name.endswith("/")]
        required = {"skills/jewel-compose-analysis/SKILL.md", "mcp/jewel-tooling-version.txt", "mcp/runtime.jar", "mcp/bootstrap.jar", "mcp/tools.json", "agent/inspection-agent.jar", "agent/bridge.jar", "META-INF/jewel-gradle.xml", "META-INF/pluginIcon.svg", "META-INF/plugin.xml", "messages/JewelToolingBundle.properties", "META-INF/LICENSE", "META-INF/NOTICE", "icons/compose.svg", "icons/compose_dark.svg", "icons/runWithCompose.svg", "icons/runWithCompose_dark.svg"}
        if len(names) != len(set(names)) or not required.issubset(names):
            raise ValueError("Plugin entries are missing or duplicated")
        if jar.read("mcp/jewel-tooling-version.txt").decode().strip() != version:
            raise ValueError("MCP version differs from the plugin version")
        verify_agent_jar(jar.read("agent/inspection-agent.jar"))
        verify_agent_jar(jar.read("agent/bridge.jar"), bridge=True)
        verify_mcp_jar(jar.read("mcp/runtime.jar"))
        verify_mcp_jar(jar.read("mcp/bootstrap.jar"), bootstrap=True)
        tools = json.loads(jar.read("mcp/tools.json"))
        if {tool["name"] for tool in tools} != {"jewel_status", "jewel_composables", "jewel_analyze", "jewel_explain"}:
            raise ValueError("Unexpected MCP tool contract")
        for name in names:
            allowed = (
                name in required
                or name in {"META-INF/MANIFEST.MF", "META-INF/dev.sebastiano.jewel.tooling_jewel-tooling.kotlin_module", "META-INF/LICENSE", "META-INF/NOTICE"}
                or name in {"colorSchemes/JewelToolingLight.xml", "colorSchemes/JewelToolingDark.xml", "icons/compose.svg", "icons/compose_dark.svg", "icons/runWithCompose.svg", "icons/runWithCompose_dark.svg"}
                or (name.startswith("dev/sebastiano/jewel/tooling/") and name.endswith(".class")
                    and not any(part in name for part in ("/e2e/", "/recording/", "Test", "Fixture", "Scenario")))
            )
            if not allowed:
                raise ValueError(f"Unexpected production JAR entry: {name}")


def verify_agent_jar(data, bridge=False):
    own = "dev/sebastiano/jewel/tooling/"
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        names = [name for name in jar.namelist() if not name.endswith("/")]
        required = {"META-INF/MANIFEST.MF", "META-INF/LICENSE", own + (
            "bridge/TraceBridge.class" if bridge else "agent/Premain.class")}
        if len(names) != len(set(names)) or not required.issubset(names):
            raise ValueError("Agent entries are missing or duplicated")
        for name in names:
            if name.startswith("META-INF/"):
                if name.endswith(".class") and not re.fullmatch(r"META-INF/versions/\d+/(module-info.class|kotlin/.*|com/fasterxml/.*)", name):
                    raise ValueError(f"Unexpected agent metadata class: {name}")
                continue
            prefixes = (own + "bridge/",) if bridge else (
                own + "agent/", own + "recording/", "kotlin/", "com/fasterxml/jackson/core/",
                "org/objectweb/asm/", "org/jetbrains/annotations/", "org/intellij/lang/annotations/")
            if not name.startswith(prefixes) or (name.startswith(own) and any(part in name for part in ("/e2e/", "Test", "Fixture", "Scenario"))):
                raise ValueError(f"Unexpected agent entry: {name}")


def verify_mcp_jar(data, bootstrap=False):
    own = "dev/sebastiano/jewel/tooling/mcp/"
    required = {"META-INF/MANIFEST.MF", "META-INF/LICENSE"}
    required |= {own + name + ".class" for name in (
        ("bootstrap/Discovery", "bootstrap/Boot") if bootstrap else ("runtime/RuntimeServer", "runtime/Bridge"))}
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        names = [name for name in jar.namelist() if not name.endswith("/")]
        if len(names) != len(set(names)) or not required.issubset(names):
            raise ValueError("MCP entries are missing or duplicated")
        if b"Class-Path:" in jar.read("META-INF/MANIFEST.MF"):
            raise ValueError("MCP runtime must not load an external manifest classpath")
        for name in names:
            if name in required:
                continue
            if bootstrap:
                if not re.fullmatch(own + r"bootstrap/(Boot|Discovery)(\$[^/]+)?\.class", name):
                    raise ValueError(f"Unexpected bootstrap entry: {name}")
                continue
            prefixes = (own + "runtime/", "kotlin/", "kotlinx/", "io/ktor/", "io/modelcontextprotocol/",
                        "io/github/oshai/", "org/slf4j/", "com/typesafe/config/",
                        "org/jetbrains/annotations/", "org/intellij/lang/annotations/", "_COROUTINE/")
            metadata = (
                name in {"DebugProbesKt.bin", "META-INF/LICENSE.txt"}
                or name.startswith(("META-INF/licenses/", "META-INF/services/", "META-INF/proguard/",
                                    "META-INF/com.android.tools/", "META-INF/maven/org.slf4j/",
                                    "META-INF/native-image/io.ktor/", "META-INF/org/jetbrains/"))
                or re.fullmatch(r"META-INF/[^/]+\.(kotlin_module|version)", name)
                or re.fullmatch(r"META-INF/versions/\d+/(module-info.class|kotlin/.*)", name)
            )
            if not metadata and not name.startswith(prefixes):
                raise ValueError(f"Unexpected isolated MCP entry: {name}")
            if name.startswith(own) and any(part in name for part in ("/e2e/", "Test", "Fixture", "Scenario")):
                raise ValueError(f"Test code in isolated MCP entry: {name}")


def verify_recording_jar(data):
    prefix = "dev/sebastiano/jewel/tooling/recording/"
    classes = {"CaptureFidelity", "CaptureStatus", "CaptureTarget", "CompositionRecorder", "EventSpill",
               "MutableSiteSummary",
               "Recording", "RecordingCodec", "RecordingError", "RecordingFiles", "RecordingFormatException",
               "InspectionFiles", "LiveTargetStatus", "LiveRuntimeState", "LiveCommandRejectedException", "LiveEndpoint", "LiveConnection", "LiveCommand", "LiveWire", "LiveRecordingServer", "LiveRecordingSession",
               "RecordingKt", "RecordingLimits", "SiteSummary", "SiteSummaryKt", "StopReason", "TraceEvent",
               "TraceSite", "TraceThread"}
    resources = {"META-INF/MANIFEST.MF", "META-INF/dev.sebastiano.jewel.tooling_recording.kotlin_module",
                 "META-INF/LICENSE", "META-INF/NOTICE"}
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        names = [name for name in jar.namelist() if not name.endswith("/")]
        required = {prefix + name + ".class" for name in ("Recording", "RecordingCodec", "CompositionRecorder")} | {"META-INF/LICENSE", "META-INF/NOTICE"}
        if len(names) != len(set(names)) or not required.issubset(names):
            raise ValueError("Recording entries are missing or duplicated")
        for name in names:
            stem = name.removeprefix(prefix).removesuffix(".class").split("$", 1)[0]
            if name not in resources and not (name.startswith(prefix) and name.endswith(".class") and stem in classes):
                raise ValueError(f"Unexpected recording JAR entry: {name}")


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
    directories = ["src/main", "e2e/driver-plugin/src", "e2e/runner/src", "fixtures/standalone/src", "fixtures/ijpl/src", "test-fixtures/compiler-metadata/src", "recording/src", "recording-compose/src", "agent/src", "agent-bridge/src", "agent-premain/src", "mcp-runtime/src", "mcp-bootstrap/src"]
    for directory in directories:
        for path in (root / directory).rglob("*"):
            if path.is_file():
                files.add(path.relative_to(root).as_posix())
    files.update([
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "config/detekt.yml", "config/detekt-compose.yml", "gradle/libs.versions.toml",
        "mcp-runtime/build.gradle.kts", "mcp-bootstrap/build.gradle.kts", "agent/build.gradle.kts", "agent-bridge/build.gradle.kts", "agent-premain/build.gradle.kts", "recording/build.gradle.kts", "recording-compose/build.gradle.kts", "e2e/driver-plugin/build.gradle.kts", "e2e/runner/build.gradle.kts", "test-fixtures/compiler-metadata/build.gradle.kts",
        "fixtures/standalone/build.gradle.kts", "fixtures/standalone/settings.gradle.kts",
        "fixtures/ijpl/.bazelrc", "fixtures/ijpl/MODULE.bazel.lock", "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar",
        "fixtures/ijpl/MODULE.bazel", "fixtures/ijpl/BUILD.bazel", "fixtures/ijpl/kotlin.bzl", "fixtures/ijpl/.bazelversion",
        "scripts/verify-trace-markers.py", "scripts/capture-retina.sh", "scripts/promote-captures.py", "scripts/prepare-bazel-fixture.py", "scripts/verify-artifacts.py",
    ])
    return sorted(files)


def verify_images(root):
    manifest = json.loads((root / "user-guide/images/manifest.json").read_text())
    if manifest["schemaVersion"] != 1:
        raise ValueError("Unsupported screenshot manifest version")
    if manifest["inputs"] != capture_inputs(root):
        raise ValueError("Capture input inventory changed; regenerate assets")
    if source_digest(root, manifest["inputs"]) != manifest["sourceSha256"]:
        raise ValueError("Capture inputs changed; regenerate Retina assets in this change")
    expected = {"standalone-editor", "standalone-ui", "ijpl-editor", "ijpl-ui", "explanation", "details-dark", "details-light", "recording-standalone", "recording-ijpl", "live-standalone", "live-ijpl", "mcp-standalone", "mcp-ijpl"}
    if len(manifest["images"]) != len(expected) or {entry["scenario"] for entry in manifest["images"]} != expected:
        raise ValueError("Missing or unexpected documentation scenario")
    for entry in manifest["images"]:
        relative = Path(entry["path"])
        if relative.is_absolute() or ".." in relative.parts or relative.parts[:2] != ("user-guide", "images"):
            raise ValueError("Image must be inside user-guide/images")
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
