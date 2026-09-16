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
    def make_zip(self, extra_jar_entry=None, extra_zip_entry=None):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as output:
            output.writestr("META-INF/plugin.xml", "<idea-plugin/>")
            output.writestr("messages/JewelToolingBundle.properties", "hint.stable=stable")
            output.writestr("dev/sebastiano/jewel/tooling/StabilityAnalysis.class", b"fixture")
            if extra_jar_entry:
                output.writestr(extra_jar_entry, b"unexpected")
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("jewel-tooling/lib/jewel-tooling-0.1.0.jar", jar.getvalue())
            if extra_zip_entry:
                output.writestr(extra_zip_entry, b"unexpected")
        return archive.getvalue()

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

    def test_second_library_rejected(self):
        with self.assertRaises(ValueError):
            self.verify_bytes(self.make_zip(extra_zip_entry="jewel-tooling/lib/spectre.jar"))


class ScreenshotProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "input.kt").write_text("original source")
        images = self.root / "docs/images"
        images.mkdir(parents=True)
        entries = []
        for scenario in ["standalone-editor", "standalone-ui", "ijpl-editor", "ijpl-ui", "explanation", "details-dark", "details-light"]:
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
