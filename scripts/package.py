"""Package reviewed source and report checksums without private tools or signing keys."""
from pathlib import Path
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parent.parent
dist = root / "dist"
dist.mkdir(exist_ok=True)
apk = dist / "WebSurf-0.4.0.apk"
assert apk.is_file(), "Build and copy the release APK first"
archive = dist / "WebSurf-0.4.0-source.zip"
files = [root / name for name in (
    "README.md", "THIRD_PARTY_NOTICES.md", "build.gradle", "settings.gradle",
    "gradle.properties", "gradlew", "gradlew.bat", ".gitignore", ".gitattributes", "app/build.gradle",
    "docs/USAGE.md", "docs/release-verification.json")]
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
manifest = {"version": "0.4.0", "package": "io.websurf.app", "min_android": "8.0",
    "artifacts": artifacts, "source_entries": source_count, "source_zip_crc": "passed",
    "verification": json.loads((root / "docs/release-verification.json").read_text(encoding="utf-8"))}
(dist / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
print(json.dumps(manifest, ensure_ascii=False, indent=2))
