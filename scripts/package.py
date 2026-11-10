"""Package reviewed source and report checksums without private tools or signing keys."""
import hashlib
import json
import subprocess
import tempfile
import zipfile
from pathlib import Path

from version import parse_properties, validate_branch


REQUIRED = {
    "README.md", "AGENTS.md", "version.properties", "THIRD_PARTY_NOTICES.md", "build.gradle",
    "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat", ".gitignore",
    ".gitattributes", "app/build.gradle", "docs/USAGE.md", "docs/ACID_DESIGN.md",
    "docs/release-verification.json",
}
FOLDERS = ("app/src", "gradle", "scripts", "assets")
BUILD_INPUTS = ("app/src/main", "app/src/release", "gradle")


def git(root, *args):
    return subprocess.check_output(["git", "--no-replace-objects", "-C", str(root), *args])


def source_index(root):
    if not (root / ".git").exists():
        raise ValueError("Packaging a release source ZIP requires a Git checkout")
    paths = sorted(REQUIRED) + list(FOLDERS)
    prefixes = tuple(folder + "/" for folder in FOLDERS)
    entries = {}
    for record in git(root, "ls-files", "--stage", "-z", "--", *paths).split(b"\0"):
        if not record:
            continue
        metadata, raw_name = record.split(b"\t", 1)
        mode, oid, stage = metadata.split()
        name = raw_name.decode("utf-8")
        parts = name.lower().split("/")
        if "__pycache__" in parts or "/screenshots/qa/" in "/" + name.lower() + "/":
            if name.startswith(tuple(folder + "/" for folder in BUILD_INPUTS)):
                raise ValueError("Excluded file in release build inputs: " + name)
            continue
        if ((name not in REQUIRED and not name.startswith(prefixes))
                or "\\" in name or {".tools", "analysis", "build", ".gradle"}.intersection(parts)
                or Path(name).suffix.lower() in {".jks", ".keystore", ".apk", ".pyc"}):
            raise ValueError("Unexpected release source path: " + name)
        if stage != b"0" or mode not in (b"100644", b"100755"):
            raise ValueError("Release source must be a regular, resolved Git file: " + name)
        entries[name] = (int(mode, 8), oid.decode("ascii"))
    missing = REQUIRED.difference(entries)
    if missing:
        raise ValueError("Required release source missing from Git: " + ", ".join(sorted(missing)))
    unstaged = git(root, "diff", "--no-ext-diff", "--name-only", "-z", "--", *paths)
    if entries.keys() & set(unstaged.decode("utf-8").split("\0")):
        raise ValueError("Stage the reviewed release source changes before packaging")
    # Android includes assets independently of Git's ignore rules.
    if git(root, "ls-files", "--others", "--directory", "--no-empty-directory", "-z", "--", *BUILD_INPUTS):
        raise ValueError("Stage all release build inputs or remove private files before packaging")
    return entries


def package(root):
    entries = source_index(root)

    def blob(name):
        return git(root, "cat-file", "blob", entries[name][1])

    identity = parse_properties(blob("version.properties").decode("utf-8-sig"))
    validate_branch(identity, root)
    version = identity["name"]
    dist = root / "dist"
    apk = dist / f"WebSurf-{version}.apk"
    if not apk.is_file():
        raise ValueError("Build and copy the release APK first")
    outputs = root / "app/build/outputs/apk/release"
    metadata = json.loads((outputs / "output-metadata.json").read_text(encoding="utf-8"))
    elements = metadata.get("elements", [])
    if len(elements) != 1:
        raise ValueError("Expected one release APK")
    built = elements[0]
    if built["versionName"] != version or built["versionCode"] != identity["code"]:
        raise ValueError("Rebuild the release APK for this version")
    apk_bytes = apk.read_bytes()
    if (outputs / built["outputFile"]).read_bytes() != apk_bytes:
        raise ValueError("Copied APK differs from the actual build")
    apk_hash = hashlib.sha256(apk_bytes).hexdigest()
    verification = json.loads(blob("docs/release-verification.json"))
    if verification.get("version") != version:
        raise ValueError("Update release verification for this version first")
    if verification.get("apk_sha256") != apk_hash:
        raise ValueError("Verify this exact APK first")

    with tempfile.TemporaryDirectory(dir=dist) as pending:
        archive = Path(pending) / f"WebSurf-{version}-source.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for name, (mode, _) in sorted(entries.items()):
                # Only bytes addressed by the captured Git object ID enter the public ZIP.
                info = zipfile.ZipInfo("WebSurf/" + name)
                info.create_system = 3
                info.external_attr = mode << 16
                output.writestr(info, blob(name), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        with zipfile.ZipFile(archive) as check:
            if check.testzip() is not None:
                raise ValueError("Source ZIP CRC failed")
            source_count = len(check.infolist())
        artifacts = [
            {"name": apk.name, "bytes": len(apk_bytes), "sha256": apk_hash},
            {"name": archive.name, "bytes": archive.stat().st_size,
             "sha256": hashlib.sha256(archive.read_bytes()).hexdigest()},
        ]
        sums = Path(pending) / "SHA256SUMS.txt"
        sums.write_text("".join(f"{a['sha256']}  {a['name']}\n" for a in artifacts), encoding="utf-8")
        manifest = {"version": version, "package": "io.websurf.app", "min_android": "8.0",
                    "artifacts": artifacts, "source_entries": source_count, "source_zip_crc": "passed",
                    "verification": verification}
        manifest_file = Path(pending) / "manifest.json"
        manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for completed in (archive, sums, manifest_file):
            completed.replace(dist / completed.name)
    return manifest


if __name__ == "__main__":
    print(json.dumps(package(Path(__file__).resolve().parent.parent), ensure_ascii=False, indent=2))
