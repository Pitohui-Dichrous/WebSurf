"""Package reviewed source and report checksums without private tools or signing keys."""
from pathlib import Path
import hashlib
import json
import zipfile
from version import load_version, validate_branch

root = Path(__file__).resolve().parent.parent
dist = root / "dist"
dist.mkdir(exist_ok=True)
identity = load_version(root)
validate_branch(identity, root)
version = identity["name"]
apk = dist / f"WebSurf-{version}.apk"
assert apk.is_file(), "Build and copy the release APK first"
outputs = root / "app/build/outputs/apk/release"
metadata = json.loads((outputs / "output-metadata.json").read_text(encoding="utf-8"))
elements = metadata.get("elements", [])
assert len(elements) == 1, "Expected one release APK"
built = elements[0]
assert built["versionName"] == version and built["versionCode"] == identity["code"], "Rebuild the release APK for this version"
assert (outputs / built["outputFile"]).read_bytes() == apk.read_bytes(), "Copied APK differs from the actual build"
archive = dist / f"WebSurf-{version}-source.zip"
verification = json.loads((root / "docs/release-verification.json").read_text(encoding="utf-8"))
assert verification.get("version") == version, "Update release verification for this version first"
assert verification.get("apk_sha256") == hashlib.sha256(apk.read_bytes()).hexdigest(), "Verify this exact APK first"
files = [root / name for name in (
    "README.md", "AGENTS.md", "version.properties", "THIRD_PARTY_NOTICES.md", "build.gradle", "settings.gradle",
    "gradle.properties", "gradlew", "gradlew.bat", ".gitignore", ".gitattributes", "app/build.gradle",
    "docs/USAGE.md", "docs/ACID_DESIGN.md", "docs/release-verification.json")]
# Public docs are explicitly listed above. Assets include the README image and Lucide originals,
# provenance and licenses; private analysis and internal verification logs are not source inputs.
for folder in ("app/src", "gradle", "scripts", "assets"):
    files.extend(p for p in (root / folder).rglob("*") if p.is_file() and "__pycache__" not in p.parts
        and not any(parent.name == "qa" and parent.parent.name == "screenshots"
                    for parent in p.parents))
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as output:
    for path in sorted(set(files)):
        relative = path.relative_to(root)
        assert not any(part in {".tools", "analysis", "build", ".gradle"} for part in relative.parts)
        assert path.suffix.lower() not in {".jks", ".keystore", ".apk", ".pyc"}
        output.write(path, "WebSurf/" + relative.as_posix())
with zipfile.ZipFile(archive) as check:
    assert check.testzip() is None, "Source ZIP CRC failed"
    source_count = len(check.namelist())
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
artifacts = [{"name": p.name, "bytes": p.stat().st_size, "sha256": sha(p)} for p in (apk, archive)]
(dist / "SHA256SUMS.txt").write_text("".join(f"{a['sha256']}  {a['name']}\n" for a in artifacts), encoding="utf-8")
manifest = {"version": version, "package": "io.websurf.app", "min_android": "8.0",
    "artifacts": artifacts, "source_entries": source_count, "source_zip_crc": "passed",
    "verification": verification}
(dist / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
print(json.dumps(manifest, ensure_ascii=False, indent=2))
