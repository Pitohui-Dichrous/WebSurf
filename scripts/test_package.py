"""Exercise public release packaging in disposable Git checkouts with synthetic inputs."""
import hashlib
import json
import os
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from package import REQUIRED, package


class SourcePackageTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="websurf-package-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name) / "project"
        self.root.mkdir()
        self.git("init", "-q", "-b", "main")
        for name in REQUIRED | {"assets/public.txt"}:
            path = self.root / name
            path.parent.mkdir(exist_ok=True, parents=True)
            path.write_bytes(b"public source\n")
        (self.root / ".gitattributes").write_text("* -text\n")
        (self.root / ".gitignore").write_text("/dist/\n/app/build/\n*.log\n*.jks\n")
        (self.root / "version.properties").write_text(
            "base=1.2.3\ncode=1\nchannel=main\nsequence=0\nbranch=main\n")
        self.version = "1.2.3-main"
        apk = b"synthetic Android package"
        verification = {"version": self.version, "apk_sha256": hashlib.sha256(apk).hexdigest()}
        (self.root / "docs/release-verification.json").write_text(json.dumps(verification))
        (self.root / "gradlew").chmod(0o755)
        self.git("add", ".")
        self.git("update-index", "--chmod=+x", "gradlew")

        self.dist = self.root / "dist"
        self.dist.mkdir()
        (self.dist / f"WebSurf-{self.version}.apk").write_bytes(apk)
        self.archive = self.dist / f"WebSurf-{self.version}-source.zip"
        outputs = self.root / "app/build/outputs/apk/release"
        outputs.mkdir(parents=True)
        (outputs / "release.apk").write_bytes(apk)
        (outputs / "output-metadata.json").write_text(json.dumps({"elements": [
            {"versionName": self.version, "versionCode": 1, "outputFile": "release.apk"}
        ]}))
        self.outside = Path(temp.name) / "outside"
        self.outside.mkdir()
        (self.outside / "public.txt").write_bytes(b"synthetic secret outside checkout")

    def git(self, *args, data=None):
        return subprocess.check_output(["git", "-C", str(self.root), *args], input=data)

    def test_public_sources_and_metadata_omit_untracked_and_ignored_files(self):
        assets = self.root / "assets"
        os.link(self.outside / "public.txt", assets / "untracked.txt")
        (assets / "ignored.log").write_bytes(b"private")
        manifest = package(self.root)
        with zipfile.ZipFile(self.archive) as result:
            expected = {"WebSurf/" + name for name in REQUIRED | {"assets/public.txt"}}
            self.assertEqual(set(result.namelist()), expected)
            self.assertEqual(result.read("WebSurf/assets/public.txt"), b"public source\n")
            self.assertEqual(result.getinfo("WebSurf/gradlew").external_attr >> 16, 0o100755)
            self.assertIsNone(result.testzip())
        self.assertEqual(manifest["source_entries"], len(expected))
        self.assertEqual(manifest["artifacts"][1]["sha256"], hashlib.sha256(self.archive.read_bytes()).hexdigest())
        self.assertEqual(json.loads((self.dist / "manifest.json").read_bytes()), manifest)

    def test_index_symlink_fails_closed_even_on_windows_without_symlink_privilege(self):
        oid = self.git("hash-object", "-w", "--stdin", data=b"../../outside/public.txt").strip().decode()
        self.git("update-index", "--add", "--cacheinfo", f"120000,{oid},assets/link.txt")
        with self.assertRaisesRegex(ValueError, "regular, resolved Git file"):
            package(self.root)
        self.assertFalse(self.archive.exists())

    def test_replaced_parent_directory_cannot_supply_archive_bytes(self):
        assets = self.root / "assets"
        self.git("update-index", "--assume-unchanged", "assets/public.txt")
        assets.rename(self.root / "original-assets")
        if os.name == "nt":
            subprocess.run(["cmd", "/d", "/c", "mklink", "/j", str(assets), str(self.outside)],
                           check=True, capture_output=True)
        else:
            assets.symlink_to(self.outside, target_is_directory=True)
        package(self.root)
        with zipfile.ZipFile(self.archive) as result:
            self.assertEqual(result.read("WebSurf/assets/public.txt"), b"public source\n")

    def test_compiled_untracked_source_must_be_staged(self):
        source = self.root / "app/src/main/java/Helper.java"
        source.parent.mkdir(parents=True)
        source.write_text("class Helper {}")
        with self.assertRaisesRegex(ValueError, "Stage all release build inputs"):
            package(self.root)
        self.assertFalse(self.archive.exists())
        self.git("add", "app/src/main/java/Helper.java")
        package(self.root)
        with zipfile.ZipFile(self.archive) as archive:
            self.assertEqual(archive.read("WebSurf/app/src/main/java/Helper.java"), b"class Helper {}")

    def test_ignored_android_asset_prevents_publication(self):
        for source_set, filename in (("main", "session.log"), ("release", "local.jks")):
            with self.subTest(source_set=source_set, filename=filename):
                asset = self.root / "app/src" / source_set / "assets" / filename
                asset.parent.mkdir(parents=True)
                asset.write_bytes(b"synthetic test input")
                with self.assertRaisesRegex(ValueError, "release build inputs"):
                    package(self.root)
                self.assertFalse(self.archive.exists())
                asset.unlink()

    def test_excluded_artwork_cannot_enter_the_apk_as_a_tracked_asset(self):
        asset = self.root / "app/src/main/assets/screenshots/qa/synthetic.png"
        asset.parent.mkdir(parents=True)
        asset.write_bytes(b"synthetic diagnostic")
        self.git("add", str(asset.relative_to(self.root)))
        with self.assertRaisesRegex(ValueError, "Excluded file in release build inputs"):
            package(self.root)
        self.assertFalse(self.archive.exists())

    def test_missing_and_unstaged_required_sources_abort_packaging(self):
        (self.root / "version.properties").write_bytes(b"unstaged edit")
        with self.assertRaisesRegex(ValueError, "Stage the reviewed"):
            package(self.root)
        self.git("rm", "-q", "--cached", "AGENTS.md")
        with self.assertRaisesRegex(ValueError, "Required release source missing"):
            package(self.root)
        self.assertFalse(self.archive.exists())

    def test_crc_failure_preserves_existing_release_outputs(self):
        names = [self.archive.name, "SHA256SUMS.txt", "manifest.json"]
        for name in names:
            (self.dist / name).write_bytes(b"previous release")
        with patch.object(zipfile.ZipFile, "testzip", return_value="WebSurf/README.md"):
            with self.assertRaisesRegex(ValueError, "CRC"):
                package(self.root)
        for name in names:
            self.assertEqual((self.dist / name).read_bytes(), b"previous release")
        self.assertEqual(len(list(self.dist.iterdir())), len(names) + 1)

    def test_source_archive_cannot_package_using_an_enclosing_repo(self):
        extracted = self.root / "extracted"
        extracted.mkdir()
        with self.assertRaisesRegex(ValueError, "requires a Git checkout"):
            package(extracted)


if __name__ == "__main__":
    unittest.main()
