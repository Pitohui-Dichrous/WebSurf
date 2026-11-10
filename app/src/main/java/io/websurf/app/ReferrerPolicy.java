package io.websurf.app;

import java.net.URI;
import java.util.Locale;

/** Browser referrer reduction for resources that have not made a network request yet. */
public final class ReferrerPolicy {
  private ReferrerPolicy() {}

  public static String forRequest(String page, String target, String policy) {
    if (!WebResource.isHttp(page) || !WebResource.isHttp(target)) return "";
    try {
      URI source = URI.create(page), destination = URI.create(target);
      String full = source.toASCIIString().split("#", 2)[0];
      String origin =
          new URI(source.getScheme(), null, source.getHost(), source.getPort(), "/", null, null)
              .toASCIIString();
      boolean same =
          source.getScheme().equalsIgnoreCase(destination.getScheme())
              && source.getHost().equalsIgnoreCase(destination.getHost())
              && port(source) == port(destination);
      boolean downgrade =
          source.getScheme().equalsIgnoreCase("https")
              && !destination.getScheme().equalsIgnoreCase("https");
      switch (policy == null ? "" : policy.trim().toLowerCase(Locale.ROOT)) {
        case "no-referrer":
          return "";
        case "same-origin":
          return same ? full : "";
        case "origin":
          return origin;
        case "strict-origin":
          return downgrade ? "" : origin;
        case "origin-when-cross-origin":
          return same ? full : origin;
        case "unsafe-url":
          return full;
        case "no-referrer-when-downgrade":
          return downgrade ? "" : full;
        case "strict-origin-when-cross-origin":
          return downgrade ? "" : same ? full : origin;
        default:
          // An unobserved request may inherit a stricter response-header policy.
          return "";
      }
    } catch (Exception ignored) {
      return "";
    }
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }
}
