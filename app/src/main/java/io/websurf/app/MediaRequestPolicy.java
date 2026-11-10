package io.websurf.app;

import android.webkit.CookieManager;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Policy shared by playback, artwork and the persisted download queue. */
public final class MediaRequestPolicy {
  private MediaRequestPolicy() {}

  public static void requireNetwork(String parent, String destination) throws IOException {
    if (!WebResource.isHttp(destination)) throw new IOException("仅支持 HTTP 或 HTTPS 媒体地址");
    if (parent.regionMatches(true, 0, "https:", 0, 6)
        && destination.regionMatches(true, 0, "http:", 0, 5))
      throw new IOException("拒绝从 HTTPS 降级到 HTTP");
    if (NativeMediaHttp.isLocalOriginCandidate(destination) && !sameOrigin(parent, destination))
      throw new IOException("拒绝跨来源请求局域网媒体");
  }

  public static boolean sameOrigin(String a, String b) {
    if (!WebResource.isHttp(a) || !WebResource.isHttp(b)) return false;
    URI x = URI.create(a), y = URI.create(b);
    return x.getScheme().equalsIgnoreCase(y.getScheme())
        && x.getHost().equalsIgnoreCase(y.getHost())
        && port(x) == port(y);
  }

  private static int port(URI u) {
    return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
  }

  static String remainingCookiePairs(String observed, String current) {
    if (observed == null || current == null || observed.isBlank() || current.isBlank()) return "";
    Set<String> allowed = new HashSet<>(Arrays.asList(current.split(";\\s*")));
    return String.join(
        "; ",
        Arrays.stream(observed.split(";\\s*")).filter(allowed::contains).toArray(String[]::new));
  }

  public static Map<String, String> headers(
      String root, String destination, Map<String, String> source) {
    return headers(root, destination, source, url -> CookieManager.getInstance().getCookie(url));
  }

  static Map<String, String> headers(
      String root,
      String destination,
      Map<String, String> source,
      Function<String, String> destinationCookies) {
    boolean same = sameOrigin(root, destination);
    boolean exact = same && root.equals(destination);
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : source.entrySet()) {
      String name = entry.getKey(), value = entry.getValue();
      if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
        continue;
      if (name.equalsIgnoreCase("Cookie") && same) {
        // For an HLS child, both decisions must agree: the browser sent this pair on the
        // first-party request AND the cookie jar permits this exact child path now.
        if (!exact) {
          value = remainingCookiePairs(value, destinationCookies.apply(destination));
        }
        if (!value.isEmpty()) result.put("Cookie", value);
      } else if (name.equalsIgnoreCase("Authorization") && exact) {
        result.put("Authorization", value);
      } else if (name.equalsIgnoreCase("Referer")) {
        String ref = same ? value : ReferrerPolicy.forRequest(value, destination, "strict-origin");
        if (!ref.isEmpty()) result.put("Referer", ref);
      } else if (name.equalsIgnoreCase("User-Agent") || name.equalsIgnoreCase("Origin")) {
        result.put(name, value);
      }
    }
    return result;
  }
}
