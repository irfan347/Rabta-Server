import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("locator", ROOT / "scripts/locate_android_project.py")
locator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(locator)

class ProjectLocatorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def project(self, folder):
        (folder/"app").mkdir(parents=True)
        (folder/"settings.gradle.kts").write_text('include(":app")')
        (folder/"app/build.gradle.kts").write_text('plugins {}')
        return folder

    def archive(self, name="rabta.zip", prefix=""):
        path = self.root/name
        with zipfile.ZipFile(path, "w") as z:
            z.writestr(prefix+"settings.gradle.kts", 'include(":app")')
            z.writestr(prefix+"app/build.gradle.kts", 'plugins {}')
        return path

    def test_root_source_takes_precedence_over_stale_zip(self):
        self.project(self.root)
        self.archive()
        self.assertEqual(locator.locate(self.root), self.root)

    def test_nested_android_source_in_server_repository(self):
        expected = self.project(self.root/"Rabta Android")
        self.assertEqual(locator.locate(self.root), expected)

    def test_flat_source_zip(self):
        self.archive()
        self.assertEqual(locator.locate(self.root), self.root/".android-source")

    def test_nested_source_zip(self):
        self.archive(prefix="rabta/")
        self.assertEqual(locator.locate(self.root), self.root/".android-source/rabta")

    def test_duplicate_archives_require_explicit_choice(self):
        self.archive("old.zip")
        self.archive("new.zip")
        with self.assertRaisesRegex(ValueError,"found 2"):
            locator.locate(self.root)

    def test_server_only_checkout_fails_with_actionable_error(self):
        (self.root/"package.json").write_text('{}')
        with self.assertRaisesRegex(ValueError,"Android source ZIP"):
            locator.locate(self.root)

    def test_zip_path_traversal_is_rejected_before_extracting(self):
        path=self.archive()
        with zipfile.ZipFile(path,"a") as z:
            z.writestr("../outside.txt","bad")
        with self.assertRaisesRegex(ValueError,"Unsafe path"):
            locator.locate(self.root)
        self.assertFalse((self.root/".android-source").exists())

    def test_ambiguous_extracted_projects_do_not_build_arbitrarily(self):
        self.project(self.root/"old")
        self.project(self.root/"new")
        with self.assertRaisesRegex(ValueError,"found 2"):
            locator.locate(self.root)

if __name__ == "__main__":
    unittest.main()
