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

  @Test
  public void remoteControlEndpointUsesActualPageTitle() {
    for (String tail :
        new String[] {
          "Remote_control.php", "remote-control.MP4", "RemoteControl", "remote%20control.php"
        })
      assertEquals(
          "海岸航拍.mp4",
          DownloadNames.choose("https://a.test/" + tail + "?id=52", null, "海岸航拍", "video/mp4"));
  }

  @Test
  public void genericResponseNameCannotOverrideMeaningfulVideoTitle() {
    assertEquals(
        "海岸航拍.mp4",
        DownloadNames.choose(
            "https://a.test/remote_control.php", "attachment; filename=Remote_control.mp4",
            "海岸航拍", "video/mp4"));
    assertEquals(
        "WebSurf 视频.mp4",
        DownloadNames.choose(
            "https://a.test/remote_control.php", null, "Remote_control", "video/mp4"));
  }

  @Test
  public void realServerFilenameStillWinsAndItsMediaTypeIsCorrect() {
    assertEquals(
        "Original camera take.mp4",
        DownloadNames.choose(
            "https://a.test/remote_control.php",
                "attachment; filename=\"Original camera take.mp4\"",
            "Different page title", "video/mp4"));
    assertEquals(
        "Original camera take.mp4",
        DownloadNames.choose(
            "https://a.test/remote_control.php",
                "attachment; filename=\"Original camera take.php\"",
            "Different page title", "video/mp4"));
    assertEquals(
        "Original camera take.mp4",
        DownloadNames.choose(
            "https://a.test/remote_control.php",
                "attachment; filename=\"Original camera take.exe\"",
            "Different page title", "video/mp4"));
  }

  @Test
  public void explicitUrlFilenameAndRealResourceTailRemainAvailable() {
    assertEquals(
        "海岸+航拍.mp4",
        DownloadNames.choose(
            "https://a.test/remote_control.php?filename=%E6%B5%B7%E5%B2%B8%2B%E8%88%AA%E6%8B%8D.mp4",
            null, "page", "video/mp4"));
    assertEquals(
        "camera-original.mp4",
        DownloadNames.choose(
            "https://a.test/camera-original.mp4?token=x", null, "page", "video/mp4"));
  }

  @Test
  public void hlsUsesEntryPlaylistNameAndAlwaysEndsInMp4() {
    assertEquals("海岸航拍.mp4", DownloadNames.mp4("https://a.test/master.m3u8", null, "海岸航拍"));
    assertEquals(
        "original.mp4",
        DownloadNames.mp4(
            "https://a.test/remote_control.php", "attachment; filename=original.m3u8", "page"));
    assertEquals("original.mp4", DownloadNames.mp4("https://a.test/original.m3u8", null, "page"));
    assertEquals("original.mp4", DownloadNames.mp4("original.m4v"));
  }

  @Test
  public void onlyExactInternalLabelsAreRejected() {
    assertEquals(
        "正确网页标题",
        DownloadNames.meaningfulTitle("Remote_control.php", "Video Player", "iframe", "正确网页标题"));
    assertEquals(
        "Remote Control Robot Review",
        DownloadNames.meaningfulTitle("Remote Control Robot Review", "fallback"));
    assertEquals("", DownloadNames.meaningfulTitle(null, "", "网页资源"));
  }

  @Test
  public void decimalInPageTitleIsNotMistakenForFileExtension() {
    assertEquals(
        "Episode 1.2.mp4",
        DownloadNames.choose(
            "https://a.test/remote_control.php", null, "Episode 1.2", "video/mp4"));
  }

  @Test
  public void malformedExtendedHeaderUsesQuotedFallbackWithEscapes() {
    assertEquals(
        "A _quoted_ take; final.mp4",
        DownloadNames.choose(
            "https://a.test/remote_control.php",
            "attachment; filename=\"A \\\"quoted\\\" take; final.mp4\"; filename*=UTF-8''%broken",
            "page",
            "video/mp4"));
  }

  @Test
  public void legacyExtendedEncodingAndLiteralPlusArePreserved() {
    assertEquals(
        "café+take.mp4",
        DownloadNames.choose(
            "https://a.test/remote_control.php",
            "attachment; filename*=ISO-8859-1'en'caf%E9+take.mp4",
            "page",
            "video/mp4"));
  }

  @Test
  public void mediaTitleAndHeaderCannotCreateAPath() {
    String result =
        DownloadNames.choose(
            "https://a.test/remote_control.php", null, "../../沿岸:拍摄/2026", "video/mp4");
    assertFalse(result.contains("/"));
    assertFalse(result.contains("\\"));
    assertFalse(result.startsWith("."));
    assertTrue(result.endsWith(".mp4"));
  }
}
