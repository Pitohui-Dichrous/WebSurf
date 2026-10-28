package io.websurf.app.download;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java filename selection, shared by downloads and tests. */
public final class DownloadNames {
  private static final Map<String, String> EXTENSIONS = new java.util.HashMap<>();

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
    String name = fromDisposition(disposition);
    if (name.trim().isEmpty()) {
      try {
        String path = URI.create(url).getRawPath();
        String tail =
            URLDecoder.decode(
                path.substring(path.lastIndexOf('/') + 1).replace("+", "%2B"),
                StandardCharsets.UTF_8.name());
        if (tail.matches("(?i).+\\.[a-z0-9]{1,8}")) name = tail;
      } catch (Exception ignored) {
      }
    }
    if (name.trim().isEmpty())
      name = title == null || title.trim().isEmpty() ? "WebSurf 下载" : title;
    name = sanitize(name);
    String ext = EXTENSIONS.get(mime);
    if (ext != null && !"bin".equals(ext) && name.matches("(?i).+\\.(php|aspx?|jsp|cgi|html?)")) {
      name = name.substring(0, name.lastIndexOf('.')) + "." + ext;
    }
    if (!name.matches("(?i).+\\.[a-z0-9]{1,8}")) name += "." + (ext == null ? "bin" : ext);
    return name;
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
    return sanitize(title == null || title.trim().isEmpty() ? "WebSurf 视频" : title)
            .replaceFirst("(?i)\\.(m3u8|mp4|ts|m4s)$", "")
        + ".mp4";
  }

  private static String fromDisposition(String value) {
    if (value == null) return "";
    Matcher star = Pattern.compile("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)").matcher(value);
    if (star.find()) {
      String v = star.group(1).trim().replaceAll("^\"|\"$", "");
      int first = v.indexOf('\''), second = first < 0 ? -1 : v.indexOf('\'', first + 1);
      if (second >= 0) {
        String charset = v.substring(0, first);
        try {
          return URLDecoder.decode(v.substring(second + 1).replace("+", "%2B"), charset);
        } catch (Exception ignored) {
        }
      }
    }
    Matcher basic =
        Pattern.compile("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))").matcher(value);
    return basic.find() ? (basic.group(1) != null ? basic.group(1) : basic.group(2).trim()) : "";
  }
}
