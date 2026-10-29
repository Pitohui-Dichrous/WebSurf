package io.websurf.app.download;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java filename selection, shared by downloads and tests. */
public final class DownloadNames {
  private static final Map<String, String> EXTENSIONS = new java.util.HashMap<>();
  private static final Set<String> PLACEHOLDERS =
      Set.of(
          "remotecontrol",
          "iframe",
          "embed",
          "player",
          "videoplayer",
          "websurf",
          "websurf下载",
          "websurf视频",
          "网页",
          "网页资源",
          "网页视频",
          "video",
          "audio",
          "file",
          "download",
          "stream",
          "master",
          "playlist",
          "index");
  private static final Pattern KNOWN_SUFFIX =
      Pattern.compile(
          "(?i)\\.(?:mp4|m4v|m3u8|ts|m4s|webm|mov|qt|mkv|mpeg|mpg|mp3|m4a|aac|ogg|wav|flac|opus|php|aspx?|jsp|cgi|html?|bin|dat)$");

  static {
    String[][] entries = {
      {"video/mp4", "mp4"},
      {"video/webm", "webm"},
      {"video/quicktime", "mov"},
      {"video/x-matroska", "mkv"},
      {"video/mp2t", "ts"},
      {"video/mpeg", "mpeg"},
      {"image/jpeg", "jpg"},
      {"image/png", "png"},
      {"image/gif", "gif"},
      {"image/webp", "webp"},
      {"image/avif", "avif"},
      {"image/svg+xml", "svg"},
      {"audio/mpeg", "mp3"},
      {"audio/mp4", "m4a"},
      {"audio/aac", "aac"},
      {"audio/ogg", "ogg"},
      {"audio/wav", "wav"},
      {"audio/flac", "flac"},
      {"application/pdf", "pdf"},
      {"application/zip", "zip"},
      {"application/vnd.android.package-archive", "apk"},
      {"text/plain", "txt"},
      {"application/json", "json"},
      {"application/octet-stream", "bin"}
    };
    for (String[] entry : entries) EXTENSIONS.put(entry[0], entry[1]);
  }

  public static String mime(String contentType, String fallback) {
    String m =
        contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (m.isEmpty() || "application/octet-stream".equals(m))
      m = fallback == null ? "" : fallback.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return m.isEmpty() ? "application/octet-stream" : m;
  }

  public static String choose(String url, String disposition, String title, String mime) {
    String type = mime(mime, "");
    boolean media =
        type.startsWith("video/") || type.startsWith("audio/") || type.contains("mpegurl");
    return extension(select(url, disposition, title, media), type);
  }

  /** Exact player/endpoint labels are not a media title; words within a real title are retained. */
  public static String meaningfulTitle(String... candidates) {
    if (candidates == null) return "";
    for (String candidate : candidates)
      if (candidate != null && !candidate.trim().isEmpty() && !placeholder(candidate))
        return candidate.trim();
    return "";
  }

  private static boolean placeholder(String name) {
    String base = KNOWN_SUFFIX.matcher(name.trim()).replaceFirst("");
    base = base.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
    return PLACEHOLDERS.contains(base);
  }

  private static String select(String url, String disposition, String title, boolean media) {
    String explicit = fromDisposition(disposition);
    String tail = "", queryName = "";
    try {
      URI uri = URI.create(url);
      String path = uri.getRawPath();
      if (path != null) {
        String value = decode(path.substring(path.lastIndexOf('/') + 1), false);
        if (value.matches("(?i).+\\.[a-z0-9]{1,8}")) tail = value;
      }
      if (uri.getRawQuery() != null) {
        for (String pair : uri.getRawQuery().split("&")) {
          int equals = pair.indexOf('=');
          if (equals <= 0) continue;
          String key = decode(pair.substring(0, equals), true);
          if ("filename".equalsIgnoreCase(key) || "file_name".equalsIgnoreCase(key)) {
            queryName = decode(pair.substring(equals + 1), true);
            break;
          }
        }
      }
    } catch (Exception ignored) {
    }
    for (String candidate : new String[] {explicit, queryName, tail})
      if (!candidate.trim().isEmpty() && (!media || !placeholder(candidate))) return candidate;
    String page = meaningfulTitle(title);
    if (!page.isEmpty()) return page;
    return media ? "WebSurf 视频" : "WebSurf 下载";
  }

  private static String decode(String value, boolean query) throws Exception {
    return URLDecoder.decode(
        query ? value : value.replace("+", "%2B"), StandardCharsets.UTF_8.name());
  }

  private static String extension(String value, String mime) {
    String name = sanitize(value);
    String ext = EXTENSIONS.get(mime);
    if (ext == null || "bin".equals(ext))
      return sanitize(name.matches("(?i).+\\.[a-z0-9]{1,8}") ? name : name + ".bin");
    int dot = name.lastIndexOf('.');
    String suffix = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    if (ext.equals(suffix)
        || ("mp4".equals(ext) && "m4v".equals(suffix))
        || ("jpg".equals(ext) && "jpeg".equals(suffix))
        || ("mpeg".equals(ext) && "mpg".equals(suffix))) return name;
    // A server script or a misleading transport suffix must not become the saved media type.
    if (KNOWN_SUFFIX.matcher(name).find()
        || EXTENSIONS.containsValue(suffix)
        || suffix.matches("(?i)exe|com|bat|cmd|sh")) name = name.substring(0, dot);
    return sanitize(name + "." + ext);
  }

  public static String sanitize(String raw) {
    String name =
        raw.replaceAll("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]", "_").trim().replaceAll("^\\.+", "");
    if (name.trim().isEmpty()) name = "WebSurf 下载";
    if (name.length() > 120) {
      int dot = name.lastIndexOf('.');
      String ext = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
      name = name.substring(0, 120 - ext.length()) + ext;
    }
    return name;
  }

  public static String mp4(String title) {
    String name = meaningfulTitle(title);
    return extension(name.isEmpty() ? "WebSurf 视频" : name, "video/mp4")
        .replaceFirst("(?i)\\.m4v$", ".mp4");
  }

  /** Only the entry playlist's response names the finished file, never an individual segment. */
  public static String mp4(String url, String disposition, String title) {
    return extension(select(url, disposition, title, true), "video/mp4")
        .replaceFirst("(?i)\\.m4v$", ".mp4");
  }

  private static String fromDisposition(String value) {
    if (value == null) return "";
    Matcher fields =
        Pattern.compile("(?i)(?:^|;)\\s*(filename\\*?)\\s*=\\s*(\"(?:\\\\.|[^\"\\\\])*\"|[^;]*)")
            .matcher(value);
    String basic = "", extended = "";
    while (fields.find()) {
      String v = fields.group(2).trim();
      if (v.startsWith("\"") && v.endsWith("\""))
        v = v.substring(1, v.length() - 1).replaceAll("\\\\(.)", "$1");
      if (!fields.group(1).endsWith("*")) {
        if (basic.isEmpty()) basic = v;
      } else {
        int first = v.indexOf('\''), second = first < 0 ? -1 : v.indexOf('\'', first + 1);
        if (second >= 0) {
          try {
            String decoded =
                URLDecoder.decode(
                    v.substring(second + 1).replace("+", "%2B"), v.substring(0, first));
            if (!decoded.trim().isEmpty()) extended = decoded;
          } catch (Exception ignored) {
          }
        }
      }
    }
    return extended.isEmpty() ? basic : extended;
  }
}
