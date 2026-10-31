"""Release workflow checks run only in temporary Git repositories."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from version import load_version, prepare, validate_branch


class VersionWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="websurf-version-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q", "-b", "酸性设计")
        self.git("config", "user.name", "Version Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.metadata("0.6.1", 7, "acid", 2, "酸性设计")
        (self.root / "docs").mkdir()
        (self.root / "README.md").write_text(
            "# WebSurf\n\nKEEP INTRO\n\n**酸性设计 · 0.6.1-acid.2 视觉预览版**\n\n"
            "Old release links\n\n## 基本功能\n\nKEEP FEATURES\n", encoding="utf-8")
        (self.root / "docs/USAGE.md").write_text(
            "# 使用\n\n版本：**0.6.1-acid.2**，旧版本。\n\n"
            "安装 `WebSurf-0.6.1-acid.2.apk`。KEEP USAGE\n", encoding="utf-8")
        self.commit()
        self.git("tag", "v0.6.1-acid.2")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args],
                                       text=True, encoding="utf-8", stderr=subprocess.STDOUT)

    def metadata(self, base, code, channel, sequence, branch):
        (self.root / "version.properties").write_text(
            f"base={base}\ncode={code}\nchannel={channel}\nsequence={sequence}\nbranch={branch}\n",
            encoding="utf-8")

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-q", "-m", "Fixture version")

    def test_branch_update_keeps_base_and_updates_docs_once(self):
        info = prepare(self.root, branch_update=True)
        self.assertEqual((info["name"], info["code"]), ("0.6.1-acid.3", 8))
        self.assertEqual(load_version(self.root), info)
        self.assertEqual(validate_branch(info, self.root), "酸性设计")
        readme = (self.root / "README.md").read_text(encoding="utf-8")
        self.assertIn("KEEP INTRO", readme)
        self.assertIn("KEEP FEATURES", readme)
        self.assertIn("v0.6.1-acid.3/WebSurf-0.6.1-acid.3.apk", readme)
        self.assertIn("KEEP USAGE", (self.root / "docs/USAGE.md").read_text(encoding="utf-8"))
        with self.assertRaisesRegex(ValueError, "already has an unreleased"):
            prepare(self.root, branch_update=True)
        with self.assertRaisesRegex(ValueError, "already has an unreleased"):
            prepare(self.root, version="0.6.1")
        self.assertEqual(load_version(self.root), info)

    def test_branch_update_uses_global_channel_ordinal_and_highest_android_code(self):
        self.git("switch", "-q", "-c", "archive-branch")
        self.metadata("0.5.1", 14, "acid", 8, "archive-branch")
        self.commit()
        self.git("tag", "v0.5.1-acid.8")
        self.git("switch", "-q", "酸性设计")
        info = prepare(self.root, branch_update=True)
        self.assertEqual(info["name"], "0.6.1-acid.9")
        self.assertEqual(info["code"], 15)

    def test_preview_rejects_base_bumps_without_changing_metadata(self):
        expected = load_version(self.root)
        for options in [{"patch": True}, {"minor": True}, {"version": "0.7.0"}]:
            with self.subTest(options=options):
                with self.assertRaisesRegex(ValueError, "Preview branches keep the base"):
                    prepare(self.root, **options)
                self.assertEqual(load_version(self.root), expected)

    def test_main_requires_new_preparation_and_has_no_ordinal(self):
        self.git("switch", "-q", "-c", "main")
        with self.assertRaisesRegex(ValueError, "another branch"):
            validate_branch(load_version(self.root), self.root)
        for options in [{}, {"branch_update": True}]:
            with self.assertRaisesRegex(ValueError, "Promoting to main requires"):
                prepare(self.root, **options)
        info = prepare(self.root, minor=True)
        self.assertEqual((info["name"], info["sequence"]), ("0.7.0-main", 0))
        self.assertEqual(validate_branch(info, self.root), "main")
        readme = (self.root / "README.md").read_text(encoding="utf-8")
        self.assertIn("下载 APK", readme)
        self.assertNotIn("视觉预览版", readme)
        self.assertIn("**WebSurf · 0.7.0**", readme)
        self.assertNotIn("此版本来自 `main` 分支", readme)
        self.assertIn("版本：**0.7.0**，正式版。", (self.root / "docs/USAGE.md").read_text(encoding="utf-8"))
        with self.assertRaisesRegex(ValueError, "already has an unreleased"):
            prepare(self.root, version="0.8.0")
        self.assertEqual(load_version(self.root), info)

    def test_new_preview_cycle_inherits_main_base_without_resetting_ordinal(self):
        self.git("switch", "-q", "-c", "main")
        main = prepare(self.root, minor=True)
        self.commit()
        self.git("tag", "v" + main["name"])
        self.git("branch", "-D", "酸性设计")
        self.git("switch", "-q", "-c", "酸性设计")
        preview = prepare(self.root, branch_update=True)
        self.assertEqual((preview["name"], preview["code"]), ("0.7.0-acid.3", 9))

    def test_detached_and_archive_builds_reproduce_persisted_identity(self):
        expected = load_version(self.root)
        self.git("checkout", "-q", "--detach", "v0.6.1-acid.2")
        self.assertIsNone(validate_branch(load_version(self.root), self.root))
        self.assertEqual(load_version(self.root), expected)
        archive = self.root / "archive"
        archive.mkdir()
        (archive / "version.properties").write_bytes((self.root / "version.properties").read_bytes())
        self.assertIsNone(validate_branch(load_version(archive), archive))
        self.assertEqual(load_version(archive), expected)
        with self.assertRaisesRegex(ValueError, "named Git branch"):
            prepare(archive, branch_update=True)

    def test_unknown_branch_needs_an_explicit_channel_and_docs_are_reusable(self):
        self.git("switch", "-q", "-c", "next-look")
        with self.assertRaisesRegex(ValueError, "Unknown branch"):
            prepare(self.root, branch_update=True)
        with self.assertRaisesRegex(ValueError, "reserved for the main"):
            prepare(self.root, minor=True, channel="main")
        first = prepare(self.root, branch_update=True, channel="neon")
        self.assertEqual(first["name"], "0.6.1-neon.1")
        self.commit()
        self.git("tag", "v" + first["name"])
        second = prepare(self.root)
        self.assertEqual(second["name"], "0.6.1-neon.2")
        self.assertEqual((self.root / "README.md").read_text(encoding="utf-8").count(
            "<!-- release-version:start -->"), 1)


if __name__ == "__main__":
    unittest.main()
