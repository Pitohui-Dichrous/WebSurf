package io.websurf.app.download;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public class HlsPlaylistTest {
  private final String base = "https://example.test/path/master.m3u8?token=a";

  @Test
  public void picksHighestVariantAndMatchesDefaultAudio() throws Exception {
    HlsPlaylist p =
        HlsPlaylist.parse(
            base,
            "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"main\",NAME=\"English,"
                + " stereo\",DEFAULT=YES,URI=\"audio/list.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360,AUDIO=\"main\"\n"
                + "low.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,AUDIO=\"main\"\n"
                + "../high/list.m3u8?k=b\n");
    assertEquals("https://example.test/high/list.m3u8?k=b", p.bestVariant().url);
    assertEquals("1920x1080", p.bestVariant().label());
    assertEquals("https://example.test/path/audio/list.m3u8", p.audioFor(p.bestVariant()).url);
    assertEquals("English, stereo", p.audioFor(p.bestVariant()).name);
  }

  @Test
  public void maintainsMediaSequenceEncryptionAndRelativePaths() throws Exception {
    HlsPlaylist p =
        HlsPlaylist.parse(
            base,
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:29\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n#EXTINF:4.0,\na.ts\n"
                + "#EXT-X-KEY:METHOD=NONE\n#EXTINF:2.5,\n//cdn.test/b.ts?q=1\n#EXT-X-ENDLIST\n");
    assertEquals(29, p.segments.get(0).sequence);
    assertEquals(30, p.segments.get(1).sequence);
    assertEquals("https://example.test/key.bin", p.segments.get(0).key.url);
    assertNull(p.segments.get(1).key);
    assertEquals("https://cdn.test/b.ts?q=1", p.segments.get(1).url);
    assertTrue(p.endList);
    assertEquals(6.5, p.duration, 0.0001);
  }

  @Test
  public void resolvesExplicitAndImplicitByteRanges() throws Exception {
    HlsPlaylist p =
        HlsPlaylist.parse(
            base,
            "#EXTM3U\n#EXT-X-MAP:URI=\"all.mp4\",BYTERANGE=\"512@0\"\n"
                + "#EXTINF:1,\n#EXT-X-BYTERANGE:100@512\nall.mp4\n"
                + "#EXTINF:1,\n#EXT-X-BYTERANGE:200\nall.mp4\n#EXT-X-ENDLIST");
    assertEquals(512, p.segments.get(0).range.offset);
    assertEquals(612, p.segments.get(1).range.offset);
    assertEquals(512, p.segments.get(0).init.range.length);
  }

  @Test
  public void flagsDiscontinuityAndLiveRatherThanSilentlyIgnoring() throws Exception {
    HlsPlaylist p =
        HlsPlaylist.parse(
            base, "#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:1,\nb.ts\n");
    assertTrue(p.discontinuity);
    assertFalse(p.endList);
  }

  @Test(expected = IOException.class)
  public void rejectsDrm() throws Exception {
    HlsPlaylist.parse(
        base,
        "#EXTM3U\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n"
            + "#EXTINF:1,\n"
            + "a.ts\n");
  }

  @Test(expected = IOException.class)
  public void rejectsImplicitRangeAcrossDifferentResources() throws Exception {
    HlsPlaylist.parse(
        base,
        "#EXTM3U\n"
            + "#EXTINF:1,\n"
            + "#EXT-X-BYTERANGE:100@0\n"
            + "a.ts\n"
            + "#EXTINF:1,\n"
            + "#EXT-X-BYTERANGE:100\n"
            + "b.ts\n");
  }

  @Test(expected = IOException.class)
  public void rejectsLocalFileUris() throws Exception {
    HlsPlaylist.parse(base, "#EXTM3U\n#EXTINF:1,\nfile:///private/file\n#EXT-X-ENDLIST");
  }

  @Test(expected = IOException.class)
  public void rejectsHtmlErrorPage() throws Exception {
    HlsPlaylist.parse(base, "<html>Forbidden</html>");
  }
}
