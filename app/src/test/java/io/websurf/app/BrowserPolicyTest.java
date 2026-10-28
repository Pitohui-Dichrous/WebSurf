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
    Map<String, String> same =
        ScopedHttpDataSource.scoped(
            "https://media.example/master.m3u8", "https://media.example/chunk.ts", headers);
    assertEquals("private=1", same.get("Cookie"));
    Map<String, String> other =
        ScopedHttpDataSource.scoped(
            "https://media.example/master.m3u8", "https://cdn.example/chunk.ts", headers);
    assertFalse(other.containsKey("Cookie"));
    assertFalse(other.containsKey("Authorization"));
    assertEquals("Browser", other.get("User-Agent"));
    assertFalse(
        ScopedHttpDataSource.sameOrigin("https://media.example/a", "http://media.example/b"));
    assertFalse(
        ScopedHttpDataSource.sameOrigin("https://media.example/a", "https://media.example:8443/b"));
    assertTrue(
        ScopedHttpDataSource.sameOrigin("https://media.example/a", "https://media.example:443/b"));
  }

  @Test
  public void addressSupportsSearchAndDefaultHttps() {
    assertEquals("https://www.baidu.com", MainActivity.normalizeAddress("www.baidu.com"));
    assertEquals("http://localhost:8765/", MainActivity.normalizeAddress("http://localhost:8765/"));
    assertEquals(
        "https://www.baidu.com/s?wd=hello+world", MainActivity.normalizeAddress("hello world"));
  }
}
