WebSurf local integration fixtures

Generate: python scripts/generate_fixtures.py
Serve:    python scripts/fixture_server.py
Device:   adb -s emulator-5560 reverse tcp:8765 tcp:8765
Open:     http://127.0.0.1:8765/

The server binds only to 127.0.0.1. It does not expose fixtures on the LAN.
Generated media: 8 second synthetic test patterns + sine audio, H.264/AAC.
This fixture directory belongs to androidTest assets, not the production APK.

The page sets an HttpOnly test cookie. Protected resources require both:
  Cookie: websurf_fixture=ready
  Referer: http://127.0.0.1:8765/

Protected endpoints:
  /protected/sample-360.mp4
  /protected/sample-720.mp4
  /protected/hls/master.m3u8
  /protected/hls/renditions/360/index.m3u8
  /protected/hls/renditions/720/index.m3u8
  /protected/encrypted/index.m3u8
  /protected/keys/aes.key
  /protected/audio-only.m4a

Replace /protected/ with /public/ to isolate media handling from header handling.
/public/cover.png provides a non-video image resource.
Range requests are supported. /api/requests provides bounded request evidence.
/health reports readiness. /manifest.json lists SHA-256 values.

Suggested device checks:
1. Enter unique text in #note and play the default 360p MP4.
2. Note window.__fixture.instance, loadCount, #note.value and video.currentTime.
3. Close WebSurf takeover without navigation. Confirm the same page instance,
   count and text remain; confirm the original page can choose MP4 720p and play.
4. Select each HLS option or use the HLS probe buttons. Download the selected
   source. Confirm an actual MP4 container with both audio and video, duration
   near 8 seconds, and the selected resolution. Do not accept filename-only
   conversion. AES output must decode and key requests must preserve headers.
5. Confirm the cover image and audio-only source are not listed as page videos.
6. Scroll to #bottom-note, enter text and repeat closing takeover. Confirm the
   scroll offset and text stay in place.

The text fields deliberately do not restore saved values after a reload.
sessionStorage persists only the load counter, so a hidden reload is detectable.
The AES key bytes 00..0f and fixed IV are intentionally public test material.
