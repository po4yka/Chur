#!/usr/bin/env python3
"""Offline checks of scripts/check-backup-rules.py against modified copies of the rule files."""

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("check_backup_rules",
                                              Path(__file__).with_name("check-backup-rules.py"))
rules = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rules)

REPOSITORY = Path(__file__).resolve().parent.parent
EXTRACTION = "apps/androidApp/src/main/res/xml/data_extraction_rules.xml"
FULL = "apps/androidApp/src/main/res/xml/backup_rules.xml"


class BackupRulesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        for name in (EXTRACTION, FULL, rules.MANIFEST):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / name, target)

    def tearDown(self):
        self.directory.cleanup()

    def edit(self, name, old, new):
        path = self.root / name
        text = path.read_text()
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, 1))

    def failures(self):
        return "\n".join(rules.run(self.root))

    def test_the_checked_in_files_pass(self):
        self.assertEqual(rules.run(self.root), [])

    def test_a_missing_section_fails(self):
        start = "<device-transfer>"
        end = "</device-transfer>"
        text = (self.root / EXTRACTION).read_text()
        (self.root / EXTRACTION).write_text(text[:text.index(start)] + text[text.index(end) + len(end):])
        self.assertIn("expected one <device-transfer>, found 0", self.failures())

    def test_a_section_without_includes_fails_even_if_the_other_is_correct(self):
        text = (self.root / EXTRACTION).read_text()
        start = text.index("<device-transfer>") + len("<device-transfer>")
        end = text.index("</device-transfer>")
        (self.root / EXTRACTION).write_text(text[:start] + "\n    " + text[end:])
        failures = self.failures()
        self.assertIn("<device-transfer> has no <include>", failures)
        self.assertNotIn("<cloud-backup> has no", failures)

    def test_a_required_include_is_checked_in_each_section(self):
        text = (self.root / EXTRACTION).read_text()
        last = text.rindex('<include domain="file" path="public/" />')
        (self.root / EXTRACTION).write_text(text[:last] + text[last:].replace(
            '<include domain="file" path="public/" />', "", 1))
        failures = self.failures()
        self.assertIn('<device-transfer> has no <include domain="file" path="public/">', failures)

    def test_device_protected_domains_fail(self):
        for domain in ("device_root", "device_file", "device_database", "device_sharedpref"):
            with self.subTest(domain=domain):
                self.tearDown()
                self.setUp()
                self.edit(FULL, "</full-backup-content>",
                          f'    <include domain="{domain}" path="notes/" />\n</full-backup-content>')
                self.assertTrue("reaches outside" in self.failures() or "device-protected" in self.failures())

    def test_vault_paths_still_fail(self):
        self.edit(EXTRACTION, '<include domain="file" path="public/" />',
                  '<include domain="file" path="vaults/" />')
        self.assertIn("names vault storage", self.failures())

    def test_dot_paths_normalize_before_the_check(self):
        for path in ("./.", ".", "./", "public/../vaults/", "./vaults/"):
            with self.subTest(path=path):
                self.tearDown()
                self.setUp()
                self.edit(FULL, "</full-backup-content>",
                          f'    <include domain="file" path="{path}" />\n</full-backup-content>')
                failures = self.failures()
                self.assertTrue(any(reason in failures for reason in
                                    ("every file of the domain", "leaves its directory", "names vault storage")),
                                failures)

    def test_namespaced_and_unknown_elements_fail(self):
        self.edit(FULL, "</full-backup-content>",
                  '    <x:include xmlns:x="urn:other" domain="file" path="vaults/" />\n</full-backup-content>')
        self.assertIn("is not a rule this check knows", self.failures())
        self.tearDown()
        self.setUp()
        self.edit(EXTRACTION, "<device-transfer>", "<device-transfer>\n        <other domain=\"root\" />")
        self.assertIn("<other> in <device-transfer> is not a rule this check knows", self.failures())
        self.tearDown()
        self.setUp()
        self.edit(EXTRACTION, "</data-extraction-rules>", "    <cross-platform-transfer />\n</data-extraction-rules>")
        self.assertIn("a section this check does not know", self.failures())

    def test_the_merged_manifest_mode_reads_a_given_manifest(self):
        merged = self.root / "merged.xml"
        text = (self.root / rules.MANIFEST).read_text().replace(
            'android:fullBackupContent="@xml/backup_rules"', 'android:fullBackupContent="@xml/library_rules"')
        merged.write_text(text)
        self.assertIn("android:fullBackupContent", "\n".join(rules.check_manifest(merged)))
        self.assertEqual(rules.check_manifest(self.root / rules.MANIFEST), [])

    def test_the_manifest_must_name_both_files(self):
        self.edit(rules.MANIFEST, 'android:dataExtractionRules="@xml/data_extraction_rules"', "")
        self.assertIn("android:dataExtractionRules", self.failures())
        self.tearDown()
        self.setUp()
        self.edit(rules.MANIFEST, 'android:fullBackupContent="@xml/backup_rules"',
                  'android:fullBackupContent="@xml/other_rules"')
        self.assertIn("android:fullBackupContent", self.failures())


if __name__ == "__main__":
    unittest.main()
