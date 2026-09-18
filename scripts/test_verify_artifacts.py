import importlib.util
import io
import json
import struct
from unittest.mock import patch
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("verify", Path(__file__).with_name("verify-artifacts.py"))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class DistributionTest(unittest.TestCase):
    def make_zip(self, extra_jar_entry=None, extra_zip_entry=None, shared=True, extra_shared_entry=None):
        bootstrap = self.mcp_jar(bootstrap=True)
        runtime = self.mcp_jar()
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as output:
            output.writestr("mcp/jewel-tooling-version.txt", "0.1.0")
            output.writestr("mcp/bootstrap.jar", bootstrap)
            output.writestr("mcp/runtime.jar", runtime)
            output.writestr("skills/jewel-compose-analysis/SKILL.md", "test skill")
            output.writestr("mcp/tools.json", json.dumps([{"name": name} for name in
                ("jewel_status", "jewel_composables", "jewel_analyze", "jewel_explain")]))
            output.writestr("META-INF/plugin.xml", "<idea-plugin/>")
            output.writestr("META-INF/jewel-gradle.xml", "<idea-plugin/>")
            for asset, entry in [("inspection-agent.jar", "agent/Premain"), ("bridge.jar", "bridge/TraceBridge")]:
                nested = io.BytesIO()
                with zipfile.ZipFile(nested, "w") as target:
                    target.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0")
                    target.writestr("META-INF/LICENSE", "Apache License, Version 2.0")
                    target.writestr("dev/sebastiano/jewel/tooling/" + entry + ".class", b"fixture")
                output.writestr("agent/" + asset, nested.getvalue())
            output.writestr("META-INF/pluginIcon.svg", '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40"/>')
            output.writestr("icons/compose.svg", '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"/>')
            output.writestr("icons/compose_dark.svg", '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"/>')
            output.writestr("icons/runWithCompose.svg", '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"/>')
            output.writestr("icons/runWithCompose_dark.svg", '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"/>')
            output.writestr("META-INF/LICENSE", "Apache License, Version 2.0")
            output.writestr("META-INF/NOTICE", "Jewel Tooling")
            output.writestr("messages/JewelToolingBundle.properties", "hint.stable=stable")
            output.writestr("dev/sebastiano/jewel/tooling/StabilityAnalysis.class", b"fixture")
            if extra_jar_entry:
                output.writestr(extra_jar_entry, b"unexpected")
        recording = io.BytesIO()
        with zipfile.ZipFile(recording, "w") as output:
            output.writestr("META-INF/LICENSE", "Apache License, Version 2.0")
            output.writestr("META-INF/NOTICE", "Jewel Tooling")
            for name in ("Recording", "RecordingCodec", "CompositionRecorder"):
                output.writestr("dev/sebastiano/jewel/tooling/recording/" + name + ".class", b"fixture")
            if extra_shared_entry:
                output.writestr(extra_shared_entry, b"unexpected")
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("jewel-tooling/lib/jewel-tooling-0.1.0.jar", jar.getvalue())
            output.writestr("jewel-tooling/lib/mcp-bootstrap.jar", bootstrap)
            if shared:
                output.writestr("jewel-tooling/lib/recording-0.1.0.jar", recording.getvalue())
            if extra_zip_entry:
                output.writestr(extra_zip_entry, b"unexpected")
        return archive.getvalue()

    def mcp_jar(self, bootstrap=False, extra=None):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0")
            jar.writestr("META-INF/LICENSE", "Apache License, Version 2.0")
            names = ("bootstrap/Discovery", "bootstrap/Boot") if bootstrap else ("runtime/RuntimeServer", "runtime/Bridge")
            for name in names:
                jar.writestr("dev/sebastiano/jewel/tooling/mcp/" + name + ".class", b"fixture")
            if extra:
                jar.writestr(extra, b"unexpected")
        return data.getvalue()

    def test_mcp_isolation_rejects_ide_compose_and_tests(self):
        for entry in ("com/intellij/openapi/project/Project.class", "androidx/compose/runtime/Composer.class",
                      "dev/sebastiano/jewel/tooling/mcp/runtime/RuntimeServerTest.class", "private-data.txt"):
            with self.subTest(entry=entry), self.assertRaises(ValueError):
                verify.verify_mcp_jar(self.mcp_jar(extra=entry))
        with self.assertRaises(ValueError):
            verify.verify_mcp_jar(self.mcp_jar(bootstrap=True, extra="kotlin/Unit.class"), bootstrap=True)

    def verify_bytes(self, contents):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plugin.zip"
            path.write_bytes(contents)
            return verify.verify_zip(path)

    def test_minimal_distribution(self):
        self.assertEqual(64, len(self.verify_bytes(self.make_zip())))

    def test_foreign_class_and_resource_rejected(self):
        for name in ("dev/sebastiano/spectre/RobotDriver.class", "private-source.txt", "dev/sebastiano/jewel/tooling/e2e/Driver.class"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.verify_bytes(self.make_zip(extra_jar_entry=name))

    def test_unapproved_library_rejected(self):
        with self.assertRaises(ValueError):
            self.verify_bytes(self.make_zip(extra_zip_entry="jewel-tooling/lib/spectre.jar"))

    def test_missing_shared_library_rejected(self):
        with self.assertRaises(ValueError):
            self.verify_bytes(self.make_zip(shared=False))

    def test_duplicate_shared_library_rejected(self):
        with self.assertWarns(UserWarning):
            data = self.make_zip(extra_zip_entry="jewel-tooling/lib/recording-0.1.0.jar")
        with self.assertRaises(ValueError):
            self.verify_bytes(data)

    def test_adapter_and_foreign_classes_rejected_from_shared_library(self):
        for name in ("dev/sebastiano/jewel/tooling/recording/OwnedCompositionTracer.class",
                     "androidx/compose/runtime/Composer.class", "com/fasterxml/jackson/core/JsonFactory.class"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.verify_bytes(self.make_zip(extra_shared_entry=name))


class ScreenshotProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "input.kt").write_text("original source")
        images = self.root / "docs/images"
        images.mkdir(parents=True)
        entries = []
        for scenario in ["standalone-editor", "standalone-ui", "ijpl-editor", "ijpl-ui", "explanation", "details-dark", "details-light", "recording-standalone", "recording-ijpl", "live-standalone", "live-ijpl", "mcp-standalone", "mcp-ijpl"]:
            data = b"\x89PNG\r\n\x1a\n" + b"\0" * 8 + struct.pack(">II", 200, 100)
            relative = "docs/images/" + scenario + ".png"
            (self.root / relative).write_bytes(data)
            entries.append({"scenario": scenario, "path": relative, "logicalWidth": 100, "logicalHeight": 50,
                            "scaleX": 2, "scaleY": 2, "sha256": verify.hashlib.sha256(data).hexdigest()})
        self.manifest = {"schemaVersion": 1, "inputs": ["input.kt"],
                         "sourceSha256": verify.source_digest(self.root, ["input.kt"]), "images": entries}
        self.write_manifest()
        self.patcher = patch.object(verify, "capture_inputs", return_value=["input.kt"])
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def write_manifest(self):
        (self.root / "docs/images/manifest.json").write_text(json.dumps(self.manifest))

    def test_matching_recorded_provenance(self):
        verify.verify_images(self.root)

    def test_changed_source_rejected(self):
        (self.root / "input.kt").write_text("changed source")
        with self.assertRaisesRegex(ValueError, "Capture inputs changed"):
            verify.verify_images(self.root)

    def test_new_source_inventory_rejected(self):
        with patch.object(verify, "capture_inputs", return_value=["input.kt", "new.kt"]):
            with self.assertRaisesRegex(ValueError, "inventory changed"):
                verify.verify_images(self.root)

    def test_duplicate_scenario_rejected(self):
        self.manifest["images"].append(self.manifest["images"][0])
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "scenario"):
            verify.verify_images(self.root)

    def test_wrong_scale_or_dimensions_rejected(self):
        for field, value in [("scaleX", 1), ("logicalWidth", 99)]:
            original = self.manifest["images"][0][field]
            self.manifest["images"][0][field] = value
            self.write_manifest()
            with self.assertRaises(ValueError):
                verify.verify_images(self.root)
            self.manifest["images"][0][field] = original


if __name__ == "__main__":
    unittest.main()
