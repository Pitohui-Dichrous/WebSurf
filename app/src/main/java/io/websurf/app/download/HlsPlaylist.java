package io.websurf.app.download;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small, strict VOD HLS parser. Unsupported media fails before any file is published. */
public final class HlsPlaylist {
  public final String url;
  public final List<Variant> variants = new ArrayList<>();
  public final List<Audio> audio = new ArrayList<>();
  public final List<Segment> segments = new ArrayList<>();
  public boolean endList;
  public boolean discontinuity;
  public double duration;

  private HlsPlaylist(String url) {
    this.url = url;
  }

  public static final class Variant {
    public final String url, resolution, audioGroup;
    public final long bandwidth;

    Variant(String u, String r, String a, long b) {
      url = u;
      resolution = r;
      audioGroup = a;
      bandwidth = b;
    }

    public String label() {
      return resolution.isEmpty() ? Math.round(bandwidth / 1000d) + " kbps" : resolution;
    }
  }

  public static final class Audio {
    public final String url, group, name;
    public final boolean preferred;

    Audio(String u, String g, String n, boolean p) {
      url = u;
      group = g;
      name = n;
      preferred = p;
    }
  }

  public static final class Range {
    public final long offset, length;

    Range(long o, long l) {
      offset = o;
      length = l;
    }
  }

  public static final class Key {
    public final String url, iv;

    Key(String u, String i) {
      url = u;
      iv = i;
    }
  }

  public static final class Init {
    public final String url;
    public final Range range;
    public final Key key;

    Init(String u, Range r, Key k) {
      url = u;
      range = r;
      key = k;
    }
  }

  public static final class Segment {
    public final String url;
    public final Range range;
    public final Key key;
    public final Init init;
    public final long sequence;
    public final double duration;

    Segment(String u, Range r, Key k, Init i, long s, double d) {
      url = u;
      range = r;
      key = k;
      init = i;
      sequence = s;
      duration = d;
    }
  }

  public static HlsPlaylist parse(String url, String text) throws IOException {
    if (!text.replace("\uFEFF", "").trim().startsWith("#EXTM3U")) {
      throw new IOException("服务器没有返回有效的 m3u8 播放列表");
    }
    HlsPlaylist p = new HlsPlaylist(url);
    Map<String, String> variant = null;
    Key key = null;
    Init init = null;
    String nextRange = null, previousRangeUrl = "";
    long sequence = 0, nextOffset = 0;
    double segmentDuration = -1;
    for (String raw : text.split("\\r?\\n")) {
      String line = raw.trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("#EXT-X-STREAM-INF:")) {
        variant = attributes(afterColon(line));
      } else if (line.startsWith("#EXT-X-MEDIA:")) {
        Map<String, String> a = attributes(afterColon(line));
        if ("AUDIO".equals(a.get("TYPE")) && a.containsKey("URI")) {
          p.audio.add(
              new Audio(
                  resolve(url, a.get("URI")),
                  a.getOrDefault("GROUP-ID", ""),
                  a.getOrDefault("NAME", "音轨"),
                  "YES".equals(a.get("DEFAULT"))));
        }
      } else if (line.startsWith("#EXT-X-SESSION-KEY:")) {
        Map<String, String> a = attributes(afterColon(line));
        if (!"AES-128".equals(a.get("METHOD"))
            || !"identity".equals(a.getOrDefault("KEYFORMAT", "identity"))) {
          throw new IOException("此视频使用受保护的加密格式，无法下载");
        }
      } else if (line.startsWith("#EXT-X-KEY:")) {
        Map<String, String> a = attributes(afterColon(line));
        String method = a.getOrDefault("METHOD", "");
        if ("NONE".equals(method)) key = null;
        else if ("AES-128".equals(method)
            && "identity".equals(a.getOrDefault("KEYFORMAT", "identity"))
            && a.containsKey("URI")) {
          key = new Key(resolve(url, a.get("URI")), a.getOrDefault("IV", ""));
        } else throw new IOException("此视频使用受保护的加密格式，无法下载");
      } else if (line.startsWith("#EXT-X-MAP:")) {
        Map<String, String> a = attributes(afterColon(line));
        if (!a.containsKey("URI")) throw new IOException("m3u8 初始化片段缺少地址");
        if (key != null && key.iv.isEmpty()) throw new IOException("加密初始化片段缺少 IV");
        init = new Init(resolve(url, a.get("URI")), parseRange(a.get("BYTERANGE"), 0), key);
      } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
        sequence = number(afterColon(line), "分片序号");
      } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
        nextRange = afterColon(line);
      } else if (line.equals("#EXT-X-ENDLIST")) {
        p.endList = true;
      } else if (line.equals("#EXT-X-DISCONTINUITY")) {
        p.discontinuity = true;
      } else if (line.equals("#EXT-X-GAP")) {
        throw new IOException("此播放列表缺少视频片段，无法导出完整文件");
      } else if (line.startsWith("#EXTINF:")) {
        try {
          segmentDuration = Double.parseDouble(afterColon(line).split(",", 2)[0]);
        } catch (NumberFormatException e) {
          throw new IOException("无效的分片时长", e);
        }
        if (!Double.isFinite(segmentDuration) || segmentDuration < 0)
          throw new IOException("无效的分片时长");
      } else if (!line.startsWith("#")) {
        String resolved = resolve(url, line);
        if (variant != null) {
          long bandwidth = number(variant.getOrDefault("BANDWIDTH", "0"), "码率");
          p.variants.add(
              new Variant(
                  resolved,
                  variant.getOrDefault("RESOLUTION", ""),
                  variant.getOrDefault("AUDIO", ""),
                  bandwidth));
          variant = null;
        } else {
          if (segmentDuration < 0) throw new IOException("分片缺少 EXTINF 时长");
          if (nextRange != null && !nextRange.contains("@") && !resolved.equals(previousRangeUrl)) {
            throw new IOException("分片范围缺少起始位置");
          }
          Range range = parseRange(nextRange, nextOffset);
          if (range != null) {
            nextOffset = range.offset + range.length;
            previousRangeUrl = resolved;
          } else previousRangeUrl = "";
          p.segments.add(new Segment(resolved, range, key, init, sequence++, segmentDuration));
          p.duration += segmentDuration;
          nextRange = null;
          segmentDuration = -1;
          if (p.segments.size() > 100000) throw new IOException("播放列表超过支持的分片数量");
        }
      }
    }
    if (variant != null) throw new IOException("清晰度条目缺少播放地址");
    if (p.variants.isEmpty() && p.segments.isEmpty()) throw new IOException("播放列表没有视频片段");
    return p;
  }

  public Variant bestVariant() {
    return Collections.max(variants, (a, b) -> Long.compare(a.bandwidth, b.bandwidth));
  }

  public Audio audioFor(Variant v) {
    Audio selected = null;
    for (Audio a : audio)
      if (a.group.equals(v.audioGroup)) {
        if (selected == null || a.preferred) selected = a;
        if (a.preferred) break;
      }
    return selected;
  }

  public static String resolve(String base, String relative) throws IOException {
    try {
      URI result = new URI(base).resolve(relative.trim());
      String scheme = result.getScheme();
      if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
          || result.getHost() == null
          || result.getRawUserInfo() != null) {
        throw new IOException("仅支持 HTTP 或 HTTPS 视频地址");
      }
      return result.toASCIIString();
    } catch (java.net.URISyntaxException | IllegalArgumentException e) {
      throw new IOException("视频地址无效", e);
    }
  }

  public static Map<String, String> attributes(String value) throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    int pos = 0;
    while (pos < value.length()) {
      int eq = value.indexOf('=', pos);
      if (eq < 0) throw new IOException("m3u8 属性格式无效");
      String name = value.substring(pos, eq).trim();
      pos = eq + 1;
      String result;
      if (pos < value.length() && value.charAt(pos) == '"') {
        int end = value.indexOf('"', ++pos);
        if (end < 0) throw new IOException("m3u8 属性引号不完整");
        result = value.substring(pos, end);
        pos = end + 1;
        if (pos < value.length() && value.charAt(pos) != ',') throw new IOException("m3u8 属性分隔符无效");
      } else {
        int end = value.indexOf(',', pos);
        if (end < 0) end = value.length();
        result = value.substring(pos, end).trim();
        pos = end;
      }
      out.put(name, result);
      if (pos < value.length()) pos++;
    }
    return out;
  }

  private static String afterColon(String v) {
    return v.substring(v.indexOf(':') + 1).trim();
  }

  private static long number(String v, String name) throws IOException {
    try {
      long n = Long.parseLong(v);
      if (n < 0) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw new IOException("无效的" + name, e);
    }
  }

  private static Range parseRange(String raw, long next) throws IOException {
    if (raw == null) return null;
    String[] parts = raw.split("@", -1);
    long length = number(parts[0], "分片范围"),
        offset = parts.length > 1 ? number(parts[1], "分片偏移") : next;
    if (parts.length > 2 || length == 0 || offset > Long.MAX_VALUE - length)
      throw new IOException("无效的分片范围");
    return new Range(offset, length);
  }
}
