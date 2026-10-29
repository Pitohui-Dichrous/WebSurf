"""Create deterministic, short local media for WebSurf device integration tests."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import zlib

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/androidTest/assets/fixtures"


def ffmpeg_path(explicit: str | None) -> str:
    candidates = [explicit, shutil.which("ffmpeg"),
        r"E:\MediaMaster\.tools\qa-python\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe"]
    for item in candidates:
        if item and Path(item).is_file():
            return str(Path(item).resolve())
    raise SystemExit("FFmpeg not found. Supply --ffmpeg with a local executable path.")


def run(executable: str, *arguments: str) -> None:
    subprocess.run([executable, "-hide_banner", "-loglevel", "error", "-y", *map(str, arguments)], check=True)


def png(path: Path) -> None:
    width, height = 320, 180
    rows = b"".join(b"\0" + b"".join(bytes((20 + x // 4, 70 + y // 2, 57)) for x in range(width)) for y in range(height))
    def chunk(kind: bytes, value: bytes) -> bytes:
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value) & 0xffffffff)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg")
    options = parser.parse_args()
    executable = ffmpeg_path(options.ffmpeg)
    media = OUT / "media"
    media.mkdir(parents=True, exist_ok=True)
    for height, width in [(360, 640), (720, 1280)]:
        target = media / f"sample-{height}.mp4"
        run(executable, "-f", "lavfi", "-i", f"testsrc2=size={width}x{height}:rate=24:duration=8",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=8",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "29", "-pix_fmt", "yuv420p",
            "-g", "48", "-keyint_min", "48", "-sc_threshold", "0",
            "-c:a", "aac", "-b:a", "64k", "-shortest", "-movflags", "+faststart", target)
        rendition = media / "hls" / "renditions" / str(height)
        rendition.mkdir(parents=True, exist_ok=True)
        run(executable, "-i", target, "-c", "copy", "-f", "hls", "-hls_time", "2",
            "-hls_playlist_type", "vod", "-hls_segment_filename", rendition / "segment-%03d.ts", rendition / "index.m3u8")
    (media / "hls/master.m3u8").write_text(
        '#EXTM3U\n#EXT-X-VERSION:3\n'
        '#EXT-X-STREAM-INF:BANDWIDTH=800000,AVERAGE-BANDWIDTH=600000,RESOLUTION=640x360,CODECS="avc1.64001e,mp4a.40.2"\n'
        'renditions/360/index.m3u8?quality=360\n'
        '#EXT-X-STREAM-INF:BANDWIDTH=1900000,AVERAGE-BANDWIDTH=1400000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"\n'
        'renditions/720/index.m3u8?quality=720\n', encoding="utf-8")
    # Public deterministic test key. This is generated fixture data, never a real secret.
    keys = media / "keys"
    keys.mkdir(exist_ok=True)
    (keys / "aes.key").write_bytes(bytes(range(16)))
    encrypted = media / "encrypted"
    encrypted.mkdir(exist_ok=True)
    key_info = encrypted / "key-info.txt"
    key_info.write_text("../keys/aes.key\n" + (keys / "aes.key").resolve().as_posix() + "\n00000000000000000000000000000001\n", encoding="utf-8")
    run(executable, "-i", media / "sample-360.mp4", "-c", "copy", "-f", "hls", "-hls_time", "2",
        "-hls_playlist_type", "vod", "-hls_key_info_file", key_info,
        "-hls_segment_filename", encrypted / "segment-%03d.ts", encrypted / "index.m3u8")
    key_info.unlink()
    run(executable, "-i", media / "sample-360.mp4", "-vn", "-c:a", "copy", "-movflags", "+faststart", media / "audio-only.m4a")
    png(media / "cover.png")
    # The only keyframe is black at zero; the frame at three seconds is red, so a sync-only
    # thumbnail seek would expose the intro instead of the requested position.
    run(executable, "-f", "lavfi", "-i", "color=c=black:size=320x180:rate=24:duration=5",
        "-vf", "drawbox=x=0:y=0:w=iw:h=ih:color=red:t=fill:enable='gte(t,2.5)'",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "29", "-pix_fmt", "yuv420p",
        "-g", "240", "-keyint_min", "240", "-sc_threshold", "0", "-an", "-movflags", "+faststart",
        media / "preview-black-intro.mp4")
    run(executable, "-f", "lavfi", "-i", "color=c=blue:size=320x180:rate=24:duration=1",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "29", "-pix_fmt", "yuv420p",
        "-an", "-movflags", "+faststart", media / "preview-short.mp4")
    manifest = {
        "durationSeconds": 8,
        "videoCodec": "H.264", "audioCodec": "AAC", "variants": [360, 720],
        "note": "Synthetic FFmpeg testsrc2 + sine media; AES test key is public fixture data.",
        "previewFixtures": {"preview-black-intro.mp4": 5, "preview-short.mp4": 1},
        "files": [
            {"path": item.relative_to(OUT).as_posix(), "bytes": item.stat().st_size,
             "sha256": hashlib.sha256(item.read_bytes()).hexdigest()}
            for item in sorted(media.rglob("*")) if item.is_file()
        ],
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fixtureRoot": str(OUT), "files": len(manifest["files"]), "bytes": sum(f["bytes"] for f in manifest["files"])}))


if __name__ == "__main__":
    main()
