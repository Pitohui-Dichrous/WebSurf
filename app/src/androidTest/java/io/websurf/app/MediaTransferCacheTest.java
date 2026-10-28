package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Media3 cache spans, with counted upstream reads and synthetic bytes only. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MediaTransferCacheTest {
  private static final int FRAGMENT = 256 * 1024;
  private Context context;
  private String source, uri;
  private Map<String, String> headers;
  private final List<Identity> identities = new ArrayList<>();

  private record Identity(String source, Map<String, String> headers) {}

  @Before
  public void uniqueTransfer() {
    context = ApplicationProvider.getApplicationContext();
    String run = UUID.randomUUID().toString();
    source = "https://media.example/page/" + run;
    uri = "https://media.example/" + run + "/clip.mp4";
    headers = Map.of(MediaTransferCache.SCOPE_HEADER, "page-a", "Cookie", "fixture_session=a");
    remember(source, headers);
  }

  @After
  public void removeOnlyTheseTransfers() {
    for (Identity identity : identities)
      MediaTransferCache.invalidate(context, identity.source(), identity.headers(), uri);
  }

  @Test
  public void committedFragmentIsPlayableWhileTheDownloadStillHasUnreadBytes() throws Exception {
    byte[] bytes = bytes(3 * FRAGMENT, 11);
    TrackingInput network = new TrackingInput(bytes);
    try (InputStream downloading = capture(headers, 0, bytes.length, network)) {
      consume(downloading, FRAGMENT + 48 * 1024);
      assertFalse(network.closed);
      assertTrue("The original transfer must still be incomplete", network.available() > 0);

      CountedUpstream upstream = new CountedUpstream(bytes);
      assertArrayEquals(
          Arrays.copyOfRange(bytes, 1024, 1024 + 64 * 1024),
          play(source, headers, upstream, 1024, 64 * 1024));
      assertEquals(
          "An already published fragment should not be fetched twice", 0, upstream.opens.get());

      CountedUpstream missing = new CountedUpstream(bytes);
      assertArrayEquals(
          Arrays.copyOfRange(bytes, 2 * FRAGMENT, 2 * FRAGMENT + 4096),
          play(source, headers, missing, 2 * FRAGMENT, 4096));
      assertEquals("Uncached playback must be able to use its upstream", 1, missing.opens.get());
    }
  }

  @Test
  public void completeResourceWithUnknownRequestedLengthEndsWithoutAnUpstreamEofRequest()
      throws Exception {
    byte[] original = bytes(3 * FRAGMENT + 17, 21);
    cacheAll(headers, original);
    CountedUpstream upstream = new CountedUpstream(bytes(original.length, 84));
    assertArrayEquals(original, play(source, headers, upstream, 0, C.LENGTH_UNSET));
    assertEquals(
        "A complete cached resource must end locally instead of requesting an EOF range",
        0,
        upstream.opens.get());
  }

  @Test
  public void beginningANewDownloadRejectsOldSpansAndLateWritesFromThePreviousRun()
      throws Exception {
    byte[] oldEntity = bytes(3 * FRAGMENT, 19), replacement = bytes(oldEntity.length, 91);
    MediaTransferCache.beginDownload(context, source, headers);
    InputStream old = capture(headers, 0, oldEntity.length, new TrackingInput(oldEntity));
    try {
      consume(old, FRAGMENT + 48 * 1024);
      MediaTransferCache.beginDownload(context, source, headers);
      CountedUpstream missing = new CountedUpstream(replacement);
      assertArrayEquals(
          Arrays.copyOf(replacement, 32768), play(source, headers, missing, 0, 32768));
      assertEquals(
          "The new run must not consume the previous entity's committed bytes",
          1,
          missing.opens.get());
      cacheAll(headers, replacement);
    } finally {
      // Closing the previous run can commit its last fragment, but only to its obsolete identity.
      old.close();
    }
    CountedUpstream upstream = new CountedUpstream(oldEntity);
    assertArrayEquals(replacement, play(source, headers, upstream, 0, C.LENGTH_UNSET));
    assertEquals(
        "Late bytes from the old run must not replace the new cached entity",
        0,
        upstream.opens.get());
  }

  @Test
  public void sameUrlDoesNotShareBytesAcrossPageScopesCookiesOrSourcePages() throws Exception {
    byte[] original = bytes(65536, 9), other = bytes(65536, 79);
    cacheAll(headers, original);
    CountedUpstream same = new CountedUpstream(other);
    assertArrayEquals(original, play(source, headers, same, 0, original.length));
    assertEquals(0, same.opens.get());

    Map<String, String> differentScope =
        Map.of(MediaTransferCache.SCOPE_HEADER, "page-b", "Cookie", "fixture_session=a");
    Map<String, String> differentCookie =
        Map.of(MediaTransferCache.SCOPE_HEADER, "page-a", "Cookie", "fixture_session=b");
    for (Map<String, String> identity : List.of(differentScope, differentCookie)) {
      remember(source, identity);
      CountedUpstream upstream = new CountedUpstream(other);
      assertArrayEquals(other, play(source, identity, upstream, 0, other.length));
      assertEquals(1, upstream.opens.get());
    }
    String differentPage = source + "/another";
    remember(differentPage, headers);
    CountedUpstream upstream = new CountedUpstream(other);
    assertArrayEquals(other, play(differentPage, headers, upstream, 0, other.length));
    assertEquals(1, upstream.opens.get());
  }

  @Test
  public void equivalentHeaderOrderAndNameCasingKeepTheSameCacheIdentity() throws Exception {
    byte[] original = bytes(32768, 31);
    cacheAll(headers, original);
    Map<String, String> reordered = new LinkedHashMap<>();
    reordered.put("cookie", "fixture_session=a");
    reordered.put("x-websurf-transfer-scope", "page-a");
    CountedUpstream upstream = new CountedUpstream(bytes(original.length, 88));
    assertArrayEquals(original, play(source, reordered, upstream, 0, original.length));
    assertEquals(0, upstream.opens.get());
  }

  @Test
  public void crossOriginTransferDoesNotCacheBytesUnderTheRootCredentials() throws Exception {
    String crossOriginSource = "https://page.example/watch/" + UUID.randomUUID();
    remember(crossOriginSource, headers);
    byte[] downloaded = bytes(65536, 17), current = bytes(downloaded.length, 94);
    try (InputStream transfer =
        MediaTransferCache.capture(
            context,
            crossOriginSource,
            headers,
            uri,
            0,
            downloaded.length,
            new TrackingInput(downloaded))) {
      assertArrayEquals(downloaded, transfer.readAllBytes());
    }
    CountedUpstream upstream = new CountedUpstream(current);
    assertArrayEquals(current, play(crossOriginSource, headers, upstream, 0, C.LENGTH_UNSET));
    assertEquals(
        "Cross-origin cookies are resolved by HTTP, never by the root cache identity",
        1,
        upstream.opens.get());
  }

  @Test
  public void anotherWriterNeverBlocksOnTheExistingHoleLock() throws Exception {
    byte[] bytes = bytes(65536, 42);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (InputStream first = capture(headers, 0, bytes.length, new TrackingInput(bytes))) {
      consume(first, 128);
      Future<byte[]> second =
          executor.submit(
              () -> {
                try (InputStream transfer =
                    capture(headers, 0, bytes.length, new TrackingInput(bytes))) {
                  return transfer.readNBytes(1024);
                }
              });
      assertArrayEquals(Arrays.copyOf(bytes, 1024), second.get(3, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void closingACancelledOrFailedTransferReleasesItsHoleForTheNextWriter() throws Exception {
    byte[] bytes = bytes(65536, 16);
    try (InputStream cancelled = capture(headers, 0, bytes.length, new TrackingInput(bytes))) {
      consume(cancelled, 1024);
    }
    MediaTransferCache.invalidate(context, source, headers, uri);
    try (InputStream failed =
        capture(
            headers,
            0,
            bytes.length,
            new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("Synthetic connection interruption");
              }
            })) {
      try {
        failed.read();
        fail("The injected connection failure must reach the downloader");
      } catch (IOException expected) {
        assertEquals("Synthetic connection interruption", expected.getMessage());
      }
    }
    MediaTransferCache.invalidate(context, source, headers, uri);
    cacheAll(headers, bytes);
    CountedUpstream upstream = new CountedUpstream(bytes(bytes.length, 93));
    assertArrayEquals(bytes, play(source, headers, upstream, 0, bytes.length));
    assertEquals(
        "A leaked hole lock would prevent the new writer from publishing", 0, upstream.opens.get());
  }

  @Test
  public void invalidatingARangeFallbackRejectsEvenLateBytesFromTheOldEntity() throws Exception {
    byte[] oldEntity = bytes(3 * FRAGMENT, 7), replacement = bytes(oldEntity.length, 97);
    InputStream old = capture(headers, 0, oldEntity.length, new TrackingInput(oldEntity));
    try {
      consume(old, FRAGMENT + 48 * 1024);
      MediaTransferCache.invalidate(context, source, headers, uri);
    } finally {
      // Completion of the old in-flight capture must not republish its pending fragment.
      old.close();
    }
    CountedUpstream upstream = new CountedUpstream(replacement);
    assertArrayEquals(
        Arrays.copyOfRange(replacement, FRAGMENT, FRAGMENT + 32768),
        play(source, headers, upstream, FRAGMENT, 32768));
    assertEquals(1, upstream.opens.get());
    cacheAll(headers, replacement);
    CountedUpstream noNetwork = new CountedUpstream(oldEntity);
    assertArrayEquals(
        Arrays.copyOfRange(replacement, FRAGMENT, FRAGMENT + 32768),
        play(source, headers, noNetwork, FRAGMENT, 32768));
    assertEquals(0, noNetwork.opens.get());
  }

  private void remember(String page, Map<String, String> requestHeaders) {
    identities.add(new Identity(page, Map.copyOf(requestHeaders)));
  }

  private InputStream capture(
      Map<String, String> requestHeaders, long offset, long length, InputStream input) {
    return MediaTransferCache.capture(context, source, requestHeaders, uri, offset, length, input);
  }

  private void cacheAll(Map<String, String> requestHeaders, byte[] bytes) throws IOException {
    try (InputStream stream = capture(requestHeaders, 0, bytes.length, new TrackingInput(bytes))) {
      assertArrayEquals(bytes, stream.readAllBytes());
    }
  }

  private byte[] play(
      String page,
      Map<String, String> requestHeaders,
      CountedUpstream upstream,
      long offset,
      int length)
      throws IOException {
    DataSource data =
        MediaTransferCache.playback(context, page, requestHeaders, upstream).createDataSource();
    try {
      data.open(new DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length).build());
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(0, length));
      byte[] buffer = new byte[8192];
      int count;
      while ((count = data.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT)
        bytes.write(buffer, 0, count);
      return bytes.toByteArray();
    } finally {
      data.close();
    }
  }

  private static void consume(InputStream stream, int bytes) throws IOException {
    byte[] buffer = new byte[32768];
    while (bytes > 0) {
      int count = stream.read(buffer, 0, Math.min(buffer.length, bytes));
      assertTrue("Transfer ended earlier than its declared length", count > 0);
      bytes -= count;
    }
  }

  private static byte[] bytes(int size, int seed) {
    byte[] bytes = new byte[size];
    for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 31 + seed);
    return bytes;
  }

  private static final class TrackingInput extends ByteArrayInputStream {
    boolean closed;

    TrackingInput(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  private static final class CountedUpstream implements DataSource.Factory {
    final AtomicInteger opens = new AtomicInteger();
    final byte[] bytes;

    CountedUpstream(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public DataSource createDataSource() {
      return new DataSource() {
        private Uri current;
        private int position, end;

        @Override
        public void addTransferListener(TransferListener listener) {}

        @Override
        public long open(DataSpec spec) throws IOException {
          opens.incrementAndGet();
          current = spec.uri;
          if (spec.position < 0 || spec.position > bytes.length)
            throw new IOException("Invalid fixture range");
          position = (int) spec.position;
          end =
              (int)
                  Math.min(
                      bytes.length,
                      spec.length == C.LENGTH_UNSET ? bytes.length : spec.position + spec.length);
          return end - position;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
          if (length == 0) return 0;
          if (position >= end) return C.RESULT_END_OF_INPUT;
          int count = Math.min(length, end - position);
          System.arraycopy(bytes, position, target, offset, count);
          position += count;
          return count;
        }

        @Override
        public Uri getUri() {
          return current;
        }

        @Override
        public void close() {
          current = null;
        }
      };
    }
  }
}
