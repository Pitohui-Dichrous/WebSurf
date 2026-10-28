package io.websurf.app.download;

import static org.junit.Assert.*;

import org.junit.Test;

public class DownloadNamesTest {
  @Test
  public void honorsUtf8ExtendedFilenameBeforeAsciiFallback() {
    assertEquals(
        "测试+视频.mov",
        DownloadNames.choose(
            "https://a.test/download",
            "attachment; filename=video.bin;"
                + " filename*=UTF-8''%E6%B5%8B%E8%AF%95%2B%E8%A7%86%E9%A2%91.mov",
            "page",
            "video/quicktime"));
  }

  @Test
  public void keepsResourceExtensions() {
    assertEquals(
        "report.pdf",
        DownloadNames.choose("https://a.test/report.pdf?key=x", null, "page", "application/pdf"));
    assertEquals(
        "photo.png", DownloadNames.choose("https://a.test/opaque", null, "photo", "image/png"));
    assertEquals(
        "archive.zip",
        DownloadNames.choose(
            "https://a.test/get", "attachment; filename=archive.zip", "page", "application/zip"));
  }

  @Test
  public void neverAllowsPathsInFilename() {
    String name =
        DownloadNames.choose(
            "https://a.test/get", "attachment; filename=\"../../evil.mp4\"", "page", "video/mp4");
    assertFalse(name.contains("/"));
    assertFalse(name.contains("\\"));
    assertFalse(name.startsWith("."));
  }

  @Test
  public void mp4NameIsStable() {
    assertEquals("Example.mp4", DownloadNames.mp4("Example.m3u8"));
    assertEquals("Example.mp4", DownloadNames.mp4("Example.mp4"));
  }

  @Test
  public void contentTypeOverridesHintExceptGenericOctets() {
    assertEquals("image/png", DownloadNames.mime("image/png; charset=binary", "video/mp4"));
    assertEquals("video/mp4", DownloadNames.mime("application/octet-stream", "video/mp4"));
  }

  @Test
  public void replacesServerScriptSuffixWithActualResourceType() {
    assertEquals(
        "get.pdf", DownloadNames.choose("https://a.test/get.php", null, "page", "application/pdf"));
  }
}
