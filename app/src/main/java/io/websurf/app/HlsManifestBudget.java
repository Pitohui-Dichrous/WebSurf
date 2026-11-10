package io.websurf.app;

import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser.DeltaUpdateException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Bound the work Media3 can perform before handing it the response. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class HlsManifestBudget {
  private static final int MAX_LINE = 16 * 1024;
  private static final int MAX_LINES = 32768;
  private static final int MAX_EXPANDED = 4 * 1024 * 1024;

  static void check(
      byte[] body,
      String base,
      Map<String, String> imports,
      HlsMediaPlaylist previous,
      long deadline)
      throws IOException {
    long largestVariable = 0;
    HlsCodecAttributes codecs = new HlsCodecAttributes();
    for (String value : imports.values())
      largestVariable = Math.max(largestVariable, value.length());
    int lines = 0, renditions = 0;
    int basePathLength = base.length();
    int query = base.indexOf('?'), fragment = base.indexOf('#');
    if (query >= 0) basePathLength = query;
    if (fragment >= 0) basePathLength = Math.min(basePathLength, fragment);
    long characters = 0, references = 0;
    boolean delta = false, variantUriPending = false;
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8))) {
      for (String raw; (raw = reader.readLine()) != null; ) {
        HlsManifestInput.checkTime(deadline);
        if (++lines > MAX_LINES || raw.length() > MAX_LINE) throw excessive();
        String line = raw.trim();
        // A multivariant parser consumes the first nonblank line after STREAM-INF as a URI,
        // even when it looks like a tag. It must not redefine a validation-side variable.
        if (!variantUriPending) codecs.check(line, imports);
        // The multivariant parser matches rendition groups against its variants.
        if (line.startsWith("#EXT-X-STREAM-INF")
            || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")
            || line.startsWith("#EXT-X-MEDIA")) {
          if (++renditions > 512) throw excessive();
        }
        int variables = occurrences(line, "{$");
        if (line.startsWith("#EXT-X-DEFINE")) {
          // A definition may expand earlier definitions. Bound its resulting value, without
          // expanding it here. Imported values have already been measured in the parent.
          largestVariable = Math.max(largestVariable, line.length() + variables * largestVariable);
          if (largestVariable > MAX_LINE) throw excessive();
        }
        int passes = 1;
        if (line.startsWith("#EXT-X-DATERANGE")) {
          // Each custom attribute makes Media3 search the line again and retain its value.
          passes += occurrences(line, "X-");
          if (passes > 64) throw excessive();
        }
        characters += (long) passes * line.length();
        if (!line.isEmpty()) {
          if (variantUriPending || !line.startsWith("#")) {
            characters += baseCost(line, 0, base.length(), basePathLength);
          } else {
            characters += attributeBases(line, "URI=", base.length(), basePathLength);
            characters += attributeBases(line, "ASSET-LIST=", base.length(), basePathLength);
          }
          variantUriPending = !variantUriPending && line.startsWith("#EXT-X-STREAM-INF");
        }
        references += (long) passes * variables;
        // A multivariant playlist parses EXT-X-MEDIA attributes after collecting definitions.
        if (characters + references * largestVariable > MAX_EXPANDED) throw excessive();
        if (line.startsWith("#EXT-X-SKIP")) {
          if (delta || previous == null) throw new DeltaUpdateException();
          delta = true;
        }
      }
    }
    if (delta) {
      // Delta updates can retain older segments across many small responses. If the combined
      // snapshot grows too large, Media3 retries with a full (non-delta) playlist.
      characters += references * largestVariable;
      for (HlsMediaPlaylist.Segment segment : previous.segments) {
        lines += 1 + segment.parts.size();
        characters += segment.title.length() + segmentSize(segment);
        for (HlsMediaPlaylist.Part part : segment.parts) characters += segmentSize(part);
        if (lines > MAX_LINES || characters > MAX_EXPANDED) throw new DeltaUpdateException();
      }
    }
  }

  private static long segmentSize(HlsMediaPlaylist.SegmentBase segment) {
    long size = segment.url.length();
    if (segment.fullSegmentEncryptionKeyUri != null)
      size += segment.fullSegmentEncryptionKeyUri.length();
    if (segment.encryptionIV != null) size += segment.encryptionIV.length();
    if (segment.initializationSegment != null) size += segmentSize(segment.initializationSegment);
    if (segment.drmInitData != null) {
      for (int i = 0; i < segment.drmInitData.schemeDataCount; i++) {
        byte[] data = segment.drmInitData.get(i).data;
        if (data != null) size += data.length;
      }
    }
    return size;
  }

  private static int occurrences(String text, String token) {
    int result = 0, at = 0;
    while ((at = text.indexOf(token, at)) >= 0) {
      result++;
      at += token.length();
    }
    return result;
  }

  private static long attributeBases(String line, String attribute, int base, int path) {
    long cost = 0;
    for (int at = 0; (at = line.indexOf(attribute, at)) >= 0; ) {
      at += attribute.length();
      cost += line.startsWith("\"", at) ? baseCost(line, at + 1, base, path) : base;
    }
    return cost;
  }

  private static int baseCost(String line, int at, int base, int path) {
    if (line.regionMatches(true, at, "https://", 0, 8)
        || line.regionMatches(true, at, "http://", 0, 7)) return 0;
    // Ordinary paths replace the signed query on the playlist URL. Empty references,
    // fragments and variable-generated addresses may retain it.
    if (at >= line.length()
        || line.charAt(at) == '"'
        || line.charAt(at) == '#'
        || line.startsWith("{$", at)) return base;
    return path;
  }

  private static IOException excessive() {
    return new IOException("播放列表结构超过限制");
  }

  private HlsManifestBudget() {}
}
