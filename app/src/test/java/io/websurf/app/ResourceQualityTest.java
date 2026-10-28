package io.websurf.app;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ResourceQualityTest {
  @Test
  public void portraitUsesShortEdgeAndKeepsActualPixels() {
    WebResource video = new WebResource("https://example.org/video.mp4");
    video.width = 1080;
    video.height = 1920;
    assertEquals("1080p · 1080 × 1920", video.qualityLabel());
  }

  @Test
  public void doesNotGuessResolutionFromFilename() {
    WebResource video = new WebResource("https://example.org/video-1080p.mp4");
    assertEquals("清晰度待识别", video.qualityLabel());
    video.height = 720;
    assertEquals("已知高度 720 px", video.qualityLabel());
  }

  @Test
  public void masterMaximumIsNotReportedAsCurrentResolution() {
    ResourcePreviewLoader.Result result = new ResourcePreviewLoader.Result();
    ResourcePreviewLoader.applyPlaylist(
        result,
        "#EXTM3U\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=640x360\n"
            + "a.m3u8\n"
            + "#EXT-X-STREAM-INF:RESOLUTION=1920x1080,BANDWIDTH=2\n"
            + "b.m3u8\n");
    assertEquals("360p / 1080p", result.availableQualities);
    assertEquals(0, result.width);
    assertEquals(0, result.height);
    WebResource video = new WebResource("https://example.org/master.m3u8");
    video.maxVariantWidth = result.maxWidth;
    video.maxVariantHeight = result.maxHeight;
    assertEquals("自适应 · 最高 1080p", video.qualityLabel());
    video.width = 640;
    video.height = 360;
    assertEquals("360p · 640 × 360 · 最高 1080p", video.qualityLabel());
  }

  @Test
  public void invalidOrUnknownPlaylistResolutionStaysUnknown() {
    ResourcePreviewLoader.Result result = new ResourcePreviewLoader.Result();
    ResourcePreviewLoader.applyPlaylist(
        result, "not a playlist\n#EXT-X-STREAM-INF:RESOLUTION=999x999");
    assertEquals(0, result.maxWidth);
    assertEquals("", result.availableQualities);
    ResourcePreviewLoader.applyPlaylist(
        result, "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=999999x1\nvideo.m3u8");
    assertEquals(0, result.maxWidth);
  }
}
