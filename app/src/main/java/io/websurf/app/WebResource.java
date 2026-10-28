package io.websurf.app;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** An observed resource, never a claim that a URL is downloadable. */
public final class WebResource {
  public final String url;
  public boolean frozenDownloadHeaders;
  public final String transferScope = java.util.UUID.randomUUID().toString();
  public String mime = "", kind = "其他", source = "网络请求", title = "", pageUrl = "";

  /** Poster belongs to this exact media URL; clear it when a DOM element changes source. */
  public String posterUrl = "", qualitySource = "", availableQualities = "";

  /** Optional document/video/source generation; a reused URL must not reuse an older preview. */
  public String previewIdentity = "";

  public int width, height;
  public int maxVariantWidth, maxVariantHeight;
  public long durationMs, previewRevision;
  public long seenAt = System.currentTimeMillis();
  public final Map<String, String> headers = new LinkedHashMap<>();

  public WebResource(String url) {
    this.url = url;
    classify();
  }

  public void classify() {
    kind = classify(url, mime);
  }

  public boolean isVideo() {
    return "视频".equals(kind);
  }

  public boolean isHls() {
    return url.toLowerCase(Locale.ROOT).split("[?#]", 2)[0].endsWith(".m3u8")
        || mime.toLowerCase(Locale.ROOT).contains("mpegurl");
  }

  public static boolean isHttp(String value) {
    if (value == null || value.length() > 16384) return false;
    try {
      URI u = new URI(value);
      return ("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme()))
          && u.getHost() != null
          && u.getUserInfo() == null;
    } catch (Exception e) {
      return false;
    }
  }

  public static String classify(String url, String mime) {
    String p = url.toLowerCase(Locale.ROOT).split("[?#]", 2)[0];
    String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
    if (m.contains("mpegurl")
        || m.startsWith("video/")
        || p.matches(".*\\.(mp4|m4v|m3u8|webm|mov|mkv|flv|mpd)$")) return "视频";
    if (m.startsWith("audio/") || p.matches(".*\\.(mp3|m4a|aac|wav|ogg|flac|opus)$")) return "音频";
    if (m.startsWith("image/") || p.matches(".*\\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)$"))
      return "图片";
    return "其他";
  }

  public String label() {
    if (!title.isBlank()) return title;
    try {
      String p = new URI(url).getPath();
      String n = p.substring(p.lastIndexOf('/') + 1);
      if (!n.isBlank()) return n;
    } catch (Exception ignored) {
    }
    return "网页资源";
  }

  public String detail() {
    return (isVideo() ? qualityLabel() + " · " : "") + formatLabel();
  }

  public String qualityLabel() {
    if (!isVideo()) return kind;
    if (width > 0 && height > 0) {
      int edge = Math.min(width, height);
      String result = edge + "p · " + width + " × " + height;
      int maximum = Math.min(maxVariantWidth, maxVariantHeight);
      if (maximum > edge) result += " · 最高 " + maximum + "p";
      return result;
    }
    if (maxVariantWidth > 0 && maxVariantHeight > 0)
      return "自适应 · 最高 " + Math.min(maxVariantWidth, maxVariantHeight) + "p";
    if (width > 0 || height > 0)
      return width > 0 ? "已知宽度 " + width + " px" : "已知高度 " + height + " px";
    return "清晰度待识别";
  }

  public String formatLabel() {
    String format = kind;
    if (isHls()) format = "HLS · 下载为 MP4";
    else {
      String path = url.split("[?#]", 2)[0];
      int dot = path.lastIndexOf('.');
      if (dot > path.lastIndexOf('/') && path.length() - dot <= 6)
        format = path.substring(dot + 1).toUpperCase(Locale.ROOT);
      else if (mime.equals("video/mp4")) format = "MP4";
      else if (mime.equals("audio/mpeg")) format = "MP3";
    }
    return format;
  }
}
