package io.websurf.app;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class BrowserPolicyTest {
  @Test
  public void rejectsNonNetworkAndEmbeddedCredentials() {
    assertFalse(WebResource.isHttp("file:///sdcard/video.mp4"));
    assertFalse(WebResource.isHttp("javascript:alert(1)"));
    assertFalse(WebResource.isHttp("https://name:password@example.org/movie.mp4"));
    assertTrue(WebResource.isHttp("https://example.org/watch?token=abc"));
  }

  @Test
  public void classifiesExtensionlessResponsesAndSignedUrls() {
    assertEquals(
        "视频",
        WebResource.classify(
            "https://cdn.example/stream?format=1", "application/vnd.apple.mpegurl"));
    assertEquals("视频", WebResource.classify("https://cdn.example/MOVIE.MP4?signature=abc", ""));
    assertEquals("音频", WebResource.classify("https://cdn.example/blob", "audio/aac"));
    assertEquals("图片", WebResource.classify("https://cdn.example/photo", "image/avif"));
    assertEquals("其他", WebResource.classify("https://cdn.example/index.js", "text/javascript"));
  }

  @Test
  public void authenticationCannotFollowCrossOriginSegments() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Cookie", "private=1");
    headers.put("Authorization", "Bearer test");
    headers.put("User-Agent", "Browser");
    headers.put("Referer", "https://page.example/watch");
    String root = "https://media.example/private/master.m3u8";
    Map<String, String> same = MediaRequestPolicy.headers(root, root, headers, url -> null);
    assertEquals("private=1", same.get("Cookie"));
    assertEquals("Bearer test", same.get("Authorization"));
    Map<String, String> sibling =
        MediaRequestPolicy.headers(
            root, "https://media.example/public/chunk.ts", headers, url -> "other=2");
    assertFalse(sibling.containsKey("Cookie"));
    assertFalse(sibling.containsKey("Authorization"));
    Map<String, String> authorized =
        MediaRequestPolicy.headers(
            root, "https://media.example/private/chunk.ts", headers, url -> "private=1; newauth=2");
    assertEquals("private=1", authorized.get("Cookie"));
    Map<String, String> other =
        MediaRequestPolicy.headers(
            root,
            "https://cdn.example/chunk.ts",
            headers,
            url -> {
              throw new AssertionError("Cross-site cookie store was accessed");
            });
    assertFalse(other.containsKey("Cookie"));
    assertFalse(other.containsKey("Authorization"));
    assertEquals("https://page.example/", other.get("Referer"));
    assertEquals("Browser", other.get("User-Agent"));
    assertFalse(
        ScopedHttpDataSource.sameOrigin("https://media.example/a", "http://media.example/b"));
    assertFalse(
        ScopedHttpDataSource.sameOrigin("https://media.example/a", "https://media.example:8443/b"));
    assertTrue(
        ScopedHttpDataSource.sameOrigin("https://media.example/a", "https://media.example:443/b"));
  }

  @Test
  public void remoteMediaNeverOpensLocalUrisOrDowngrades() throws Exception {
    String root = "https://media.example/main.m3u8";
    for (String child :
        new String[] {
          "content://provider/item",
          "file:///private/file",
          "data:audio/mp4,abcd",
          "asset:///packaged",
          "HTTP://cdn.example/cleartext"
        }) {
      assertThrows(java.io.IOException.class, () -> MediaRequestPolicy.requireNetwork(root, child));
      assertThrows(
          java.io.IOException.class,
          () -> io.websurf.app.download.HlsPlaylist.resolve(root, child));
    }
    MediaRequestPolicy.requireNetwork(root, "https://cdn.example/segment");
    MediaRequestPolicy.requireNetwork("http://media.example/list", "http://cdn.example/segment");
  }

  @Test
  public void onlyExactObservedFirstDocumentRequestsSurviveInitialNavigation() {
    BrowserTab tab = new BrowserTab();
    tab.initialPage = "https://app.example/watch?part=1#start";
    String page = "https://app.example/watch?part=1";
    String media = "https://app.example/video.mp4";
    Map<String, String> browser = Map.of("referer", page);
    assertTrue(tab.initialRequest(media, browser));
    assertFalse(tab.initialRequest("https://third.example/video", browser));
    assertFalse(tab.initialRequest(media, Map.of("Referer", "https://app.example/watch?part=2")));
    assertFalse(tab.initialRequest(media, Map.of()));
    WebResource observed = new WebResource(media), hint = new WebResource(media + "?hint");
    observed.requestHeadersCaptured = true;
    observed.headers.putAll(browser);
    tab.resources.put(media, observed);
    tab.resources.put(hint.url, hint);
    tab.epoch = 1;
    tab.resetResources(page);
    assertEquals(Map.of(media, observed), tab.resources);
    tab.epoch = 2;
    tab.resetResources(page);
    assertTrue(tab.resources.isEmpty());
    assertFalse(tab.initialRequest(media, browser));
    BrowserTab popup = new BrowserTab();
    assertFalse(popup.initialRequest(media, browser));
  }

  @Test
  public void registeredVideoSourceCanLoseCookiesButCannotGainNewPairs() {
    VideoTarget source = new VideoTarget();
    source.sourceCookies = "login=old; session=ok";
    assertEquals(
        "login=old; session=ok", source.remainingSourceCookies("session=ok; login=old; other=new"));
    assertEquals("session=ok", source.remainingSourceCookies("session=ok; login=new"));
    assertEquals("", source.remainingSourceCookies(null));
    assertEquals("", new VideoTarget().remainingSourceCookies("login=old; session=ok"));
  }

  @Test
  public void addressSupportsSearchAndDefaultHttps() {
    assertEquals("https://www.baidu.com", MainActivity.normalizeAddress("www.baidu.com"));
    assertEquals("http://localhost:8765/", MainActivity.normalizeAddress("http://localhost:8765/"));
    assertEquals(
        "https://cn.bing.com/search?q=hello+world&ensearch=0",
        MainActivity.normalizeAddress("hello world"));
  }
}
