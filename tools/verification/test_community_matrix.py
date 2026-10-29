"""Regression checks for validation evidence, without launching a game."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import community_matrix as matrix


class CommunityEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.base = self.root / "external/community-compat"
        self.variant = "mihile-1.20.1"
        self.directory = self.base / self.variant
        self.directory.mkdir(parents=True)
        jar = self.directory / "dependency.jar"
        jar.write_bytes(b"fixture identity")
        (self.directory / "inputs.properties").write_text(
            f"vssVoxyJar={jar.as_posix()}\nvssSodiumJar={jar.as_posix()}\n", encoding="utf-8")

    def result(self, stage):
        return json.loads((self.directory / "validation" / stage / "report.json").read_text())

    def contract(self, xml, digests=("same", "same"), code=0):
        folder = self.root / "build/1.20.1/test-results/fabricContractTest"
        folder.mkdir(parents=True)
        (folder / "TEST-fixture.xml").write_text(xml, encoding="utf-8")
        with patch.object(matrix, "source_digest", side_effect=digests), \
                patch.object(matrix.subprocess, "run", return_value=SimpleNamespace(returncode=code)):
            return matrix.run(self.root, self.variant, "contracts", self.base)

    def test_all_skipped_contracts_are_not_reported_as_compatible(self):
        passed = self.contract('<testsuite tests="1" skipped="1"><testcase name="optional"><skipped/></testcase></testsuite>')
        self.assertFalse(passed)
        self.assertEqual("failed", self.result("contracts")["status"])

    def test_successful_tests_do_not_validate_sources_changed_during_run(self):
        self.assertFalse(self.contract('<testsuite tests="1"/>', digests=("before", "after")))
        self.assertFalse(self.result("contracts")["sourceUnchanged"])

    def test_failed_client_launch_cannot_reuse_an_old_passed_title_report(self):
        output = self.directory / "validation/title"
        output.mkdir(parents=True)
        (output / "client-smoke.json").write_text('{"status":"passed"}', encoding="utf-8")
        with patch.object(matrix, "source_digest", return_value="same"), \
                patch.object(matrix.subprocess, "run", return_value=SimpleNamespace(returncode=1)):
            self.assertFalse(matrix.run(self.root, self.variant, "title", self.base))
        self.assertIsNone(self.result("title")["probe"])

    def test_current_executed_contracts_retain_dependency_identity(self):
        self.assertTrue(self.contract('<testsuite tests="1"/>'))
        report = self.result("contracts")
        self.assertEqual("passed", report["status"])
        self.assertEqual(64, len(report["inputs"]["vssVoxyJar"]["sha256"]))
        self.assertFalse(report["gameplayValidated"])


if __name__ == "__main__":
    unittest.main()
