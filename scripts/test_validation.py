"""Behavioral contracts for the portable validation runner."""
import importlib.util
from pathlib import Path
import subprocess
import unittest

SPEC = importlib.util.spec_from_file_location("validation", Path(__file__).with_name("validate.py"))
validation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validation)


class ValidationPlanTest(unittest.TestCase):
    def test_non_display_profiles_do_not_select_e2e_tests(self):
        for profile in ("fast", "full"):
            plan = validation.commands(profile)
            flattened = [argument for command in plan for argument in command]
            self.assertNotIn(":e2e:runner:test", flattened)
            self.assertNotIn("test", flattened)
            for command in plan:
                if "fixtures/standalone" in command:
                    self.assertNotIn(":test", command)

    def test_full_retains_fast_checks_and_package_gates(self):
        fast = validation.commands("fast")
        full = validation.commands("full")
        self.assertEqual(fast, full[:len(fast)])
        flattened = [argument for command in full for argument in command]
        for task in (":test", ":agent:test", ":recording:test", ":recording-compose:test",
                     ":verifyPlugin", ":verifyPluginStructure", ":buildPlugin"):
            self.assertIn(task, flattened)
        for project in validation.KOTLIN_PROJECTS:
            for name in ("ktfmtCheck", "detekt", "detektMain", "detektTest"):
                self.assertIn(f"{project}:{name}", flattened)
        self.assertEqual(full[-1][1:], ["scripts/verify-artifacts.py", "--images"])
        self.assertEqual(full[-2][1:], ["scripts/prepare-distribution.py"])

    def test_e2e_orders_real_fixture_preparation_before_maintained_suite(self):
        plan = validation.commands("e2e", ide_path="/a path/IDE.app")
        self.assertIn(":e2e:driver-plugin:exportFixtureSdk", plan[0])
        self.assertIn("scripts/prepare-bazel-fixture.py", plan[1])
        self.assertIn("fixtures/standalone", plan[2])
        self.assertIn(":test", plan[2])
        self.assertIn("scripts/verify-trace-markers.py", plan[3])
        self.assertEqual(plan[4][1:4], [":e2e:runner:test", "--tests", "*JewelTargetsTest*"])
        self.assertEqual(plan[4][-1], "-PtestIdePath=/a path/IDE.app")

    def test_windows_selects_batch_wrapper_and_keeps_arguments_separate(self):
        plan = validation.commands("full", windows=True, python="C:/Python Folder/python.exe")
        self.assertEqual(plan[0][0], "C:/Python Folder/python.exe")
        self.assertTrue(any(command[0] == "gradlew.bat" for command in plan))
        self.assertFalse(any(command[0] == "./gradlew" for command in plan))

    def test_execution_stops_at_failure_and_uses_repository_cwd(self):
        calls = []
        def runner(command, **kwargs):
            calls.append((command, kwargs))
            raise subprocess.CalledProcessError(1, command)
        with self.assertRaises(subprocess.CalledProcessError):
            validation.execute([["first"], ["second"]], runner)
        self.assertEqual(calls, [(["first"], {"cwd": validation.ROOT, "check": True})])

    def test_unknown_profile_is_rejected(self):
        with self.assertRaises(ValueError):
            validation.commands("publish")
