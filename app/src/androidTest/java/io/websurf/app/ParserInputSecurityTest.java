package io.websurf.app;

import static org.junit.Assert.*;

import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser.DeltaUpdateException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class ParserInputSecurityTest {
  private static final Uri URL = Uri.parse("https://media.example/list.m3u8");
  private static final String MEDIA = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n";
  private static final String MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow.m3u8\n";

  private static ByteArrayInputStream input(String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void bridgeChecksNestingWhileReadingStandardJson() throws Exception {
    // A small, safe witness for the previously accepted nesting pattern.
    String shallowAttack = "{\"unused\":" + "[".repeat(32) + "0" + "]".repeat(32) + "}";
    assertTrue(new JSONObject(shallowAttack).has("unused"));
    for (String invalid :
        new String[] {
          shallowAttack,
          "{\"x\":" + "[{\"y\":".repeat(2000) + "null" + "}]".repeat(2000) + "}",
          "{/*comment*/'x':false}",
          "{x:[1,,2]}",
          "{\"x\":null}[]",
          "{\"x\":[" + "{},".repeat(2000) + "{}]}"
        }) {
      assertThrows(Exception.class, () -> BridgeMessage.parse(invalid, null));
    }
    assertNull(BridgeMessage.parse("x".repeat(32769), null));
    JSONObject event =
        new JSONObject()
            .put("text", "\\\"[#]/*}{**/")
            .put("mediaId", 9007199254740991L)
            .put("rate", 1.25);
    String prefix = BridgeMessage.prefix("b9873f8a-4b78-44ef-96ce-b72c545cd75e");
    // Even malformed/deep input is dropped without invoking the JSON decoder.
    assertNull(BridgeMessage.parse("{\"seal\":\"wrong\",\"a\":[invalid", prefix));
    assertNull(BridgeMessage.parse("{\"payload\":[[[[[[", prefix));
    assertThrows(Exception.class, () -> BridgeMessage.parse(prefix + "\"x\":[invalid", prefix));
    JSONObject decoded =
        BridgeMessage.parse(prefix + "\"observation\":" + event + ",\"epoch\":null}", prefix);
    assertEquals(event.getString("text"), decoded.getJSONObject("observation").getString("text"));
    assertEquals(9007199254740991L, decoded.getJSONObject("observation").getLong("mediaId"));
    assertEquals(1.25, decoded.getJSONObject("observation").getDouble("rate"), 0);
    assertTrue(decoded.isNull("epoch"));
  }

  @Test
  public void declaredAndUnknownLengthManifestsAreBoundedButMediaIsNot() throws Exception {
    byte[] bytes = new byte[HlsManifestInput.MAX_BYTES + 8192];
    var sources = HlsManifestInput.sources(() -> new ByteArrayDataSource(bytes));
    DataSource manifest = sources.createDataSource(C.DATA_TYPE_MANIFEST);
    try {
      assertThrows(IOException.class, () -> manifest.open(new DataSpec(URL)));
    } finally {
      manifest.close();
    }
    DataSource segment = sources.createDataSource(C.DATA_TYPE_MEDIA);
    try {
      assertEquals(bytes.length, segment.open(new DataSpec(URL)));
    } finally {
      segment.close();
    }
    ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
    assertThrows(
        IOException.class,
        () -> new NetworkHlsParserFactory().createPlaylistParser().parse(URL, stream));
    assertEquals(bytes.length - HlsManifestInput.MAX_BYTES - 1, stream.available());
  }

  @Test
  public void aTrickleCannotRenewTheManifestDeadline() {
    AtomicInteger reads = new AtomicInteger();
    InputStream drip =
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError();
          }

          @Override
          public int read(byte[] data, int offset, int length) {
            reads.incrementAndGet();
            SystemClock.sleep(20);
            data[offset] = 'x';
            return 1;
          }
        };
    assertThrows(
        IOException.class,
        () -> HlsManifestInput.read(drip, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
    assertTrue(reads.get() > 0 && reads.get() < 10);
  }

  @Test
  public void entryLineAndVariableLimitsApplyBeforeMedia3BuildsItsPlaylist() throws Exception {
    String manyVariants = "#EXTM3U\n" + "#EXT-X-STREAM-INF:BANDWIDTH=1000\na.m3u8\n".repeat(513);
    assertNotNull(
        new DefaultHlsPlaylistParserFactory()
            .createPlaylistParser()
            .parse(URL, input(manyVariants)));
    var parser = new NetworkHlsParserFactory().createPlaylistParser();
    HlsMultivariantPlaylist grouped =
        (HlsMultivariantPlaylist)
            parser.parse(
                URL,
                input(
                    "#EXTM3U\n"
                        + "#EXT-X-DEFINE:NAME=\"c\",VALUE=\"avc1.64001f, mp4a.40.2\"\n"
                        + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",URI=\"en.m3u8\"\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"a\",CODECS=\"{$c}\"\n"
                        + "v.m3u8\n"));
    assertEquals("avc1.64001f, mp4a.40.2", grouped.variants.get(0).format.codecs);
    assertEquals(1, grouped.audios.size());
    StringBuilder doubling =
        new StringBuilder("#EXTM3U\n#EXT-X-DEFINE:NAME=\"v0\",VALUE=\"abc\"\n");
    for (int i = 1; i <= 24; i++)
      doubling
          .append("#EXT-X-DEFINE:NAME=\"v")
          .append(i)
          .append("\",VALUE=\"")
          .append(("{$v" + (i - 1) + "}").repeat(2))
          .append("\"\n");
    for (String invalid :
        new String[] {
          manyVariants,
          MASTER.replace(
              "BANDWIDTH=1000", "BANDWIDTH=1000,CODECS=\"avc1" + " ".repeat(15000) + "b,mp4a\""),
          MEDIA + "#EXT-X-PART:DURATION=0.5,URI=\"part.ts\"\n".repeat(32768),
          MEDIA + "a".repeat(20 * 1024) + ".ts\n",
          doubling + "#EXT-X-STREAM-INF:BANDWIDTH=1000\n{$v24}.m3u8\n"
        }) {
      assertThrows(IOException.class, () -> parser.parse(URL, input(invalid)));
    }
  }

  @Test
  public void importedVariablesAndDeltaReloadsUseTheSameBudgets() throws Exception {
    var factory = new NetworkHlsParserFactory();
    String token = "a".repeat(2048);
    HlsMultivariantPlaylist master =
        (HlsMultivariantPlaylist)
            factory
                .createPlaylistParser()
                .parse(
                    URL,
                    input(
                        MASTER.replace(
                            "#EXTM3U\n",
                            "#EXTM3U\n#EXT-X-DEFINE:NAME=\"x\",VALUE=\"" + token + "\"\n")));
    var parser = factory.createPlaylistParser(master, null);
    String imported = MEDIA + "#EXT-X-DEFINE:IMPORT=\"x\"\n";
    String one = "#EXTINF:4,\n{$x}.ts\n";
    HlsMediaPlaylist first = (HlsMediaPlaylist) parser.parse(URL, input(imported + one));
    assertEquals(token + ".ts", first.segments.get(0).url);
    assertThrows(IOException.class, () -> parser.parse(URL, input(imported + one.repeat(2200))));
    String delta = "#EXT-X-SKIP:SKIPPED-SEGMENTS=1\n";
    var update = factory.createPlaylistParser(master, first);
    HlsMediaPlaylist next =
        (HlsMediaPlaylist) update.parse(URL, input(MEDIA + delta + "#EXTINF:4,\nnext.ts\n"));
    assertEquals(2, next.segments.size());
    assertThrows(
        DeltaUpdateException.class, () -> update.parse(URL, input(MEDIA + delta.repeat(2))));
  }
}
