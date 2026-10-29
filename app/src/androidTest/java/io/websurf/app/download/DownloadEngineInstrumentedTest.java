package io.websurf.app.download;

import static org.junit.Assert.*;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.websurf.app.MainActivity;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DownloadEngineInstrumentedTest {
  private Context context;
  private FixtureServer server;
  private boolean hadThreads;
  private int oldThreads;
  private final List<Uri> outputs = new ArrayList<>();
  private final List<String> storedIds = new ArrayList<>();

  @Before
  public void setup() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    android.content.SharedPreferences preferences =
        context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    hadThreads = preferences.contains("download_threads");
    oldThreads = preferences.getInt("download_threads", 8);
    assertTrue(preferences.edit().putInt("download_threads", 4).commit());
    server = new FixtureServer();
  }

  @After
  public void cleanup() throws Exception {
    if (server != null) server.close();
    for (Uri uri : outputs) context.getContentResolver().delete(uri, null, null);
    for (String id : storedIds) DownloadStore.remove(context, id);
    android.content.SharedPreferences.Editor edit =
        context.getSharedPreferences("browser", Context.MODE_PRIVATE).edit();
    if (hadThreads) edit.putInt("download_threads", oldThreads);
    else edit.remove("download_threads");
    assertTrue(edit.commit());
  }

  @Test
  public void masterHlsSelectsHighestAndPublishesPlayableMp4() throws Exception {
    DownloadStore.Job job = download("/redirect");
    assertMp4(job, true);
    assertTrue(server.requests.contains("/segment03.ts"));
    assertFalse(server.requests.contains("/bad-low.m3u8"));
    assertEquals(0, server.rejected.get());
  }

  @Test
  public void aes128SequenceIvExportsRealMp4() throws Exception {
    DownloadStore.Job job = download("/aes.m3u8");
    assertMp4(job, true);
    assertTrue(server.requests.contains("/key.bin"));
    long encrypted =
        server.files.entrySet().stream()
            .filter(e -> e.getKey().startsWith("/enc-segment"))
            .mapToLong(e -> e.getValue().length)
            .sum();
    assertEquals(
        "Download bytes must count ciphertext transport, not decrypted output",
        encrypted,
        job.bytes);
  }

  @Test
  public void fragmentedMp4IsConvertedToStandaloneMp4() throws Exception {
    assertMp4(download("/fmp4.m3u8"), true);
  }

  @Test
  public void separateAudioRenditionIsIncluded() throws Exception {
    assertMp4(download("/separate.m3u8"), true);
  }

  @Test
  public void byteRangeSegmentsExportCompleteMp4() throws Exception {
    assertMp4(download("/ranges.m3u8"), true);
  }

  @Test
  public void pdfFilenameAndBytesArePreserved() throws Exception {
    DownloadStore.Job job = download("/document");
    assertEquals("测试文档.pdf", job.fileName);
    assertEquals("application/pdf", job.mimeType);
    try (InputStream input =
        context.getContentResolver().openInputStream(Uri.parse(job.outputUri))) {
      assertArrayEquals(server.pdf, read(input));
    }
  }

  @Test
  public void remoteControlEndpointPublishesPageTitleAndOriginalVideoBytes() throws Exception {
    byte[] video;
    try (InputStream input =
        InstrumentationRegistry.getInstrumentation()
            .getContext()
            .getAssets()
            .open("fixtures/media/sample-360.mp4")) {
      video = read(input);
    }
    server.files.put("/Remote_control.php", video);
    server.contentTypes.put("/Remote_control.php", "video/mp4");
    server.dispositions.put("/Remote_control.php", "attachment; filename=Remote_control.mp4");
    DownloadStore.Job job = job("/Remote_control.php");
    job.title = "海岸航拍-" + job.id;
    runNamed(job);
    assertPublishedName(job, job.title + ".mp4");
    try (InputStream input =
        context.getContentResolver().openInputStream(Uri.parse(job.outputUri))) {
      assertArrayEquals(video, read(input));
    }
  }

  @Test
  public void meaningfulResponseFilenameWinsOverEndpointAndPageTitle() throws Exception {
    try (InputStream input =
        InstrumentationRegistry.getInstrumentation()
            .getContext()
            .getAssets()
            .open("fixtures/media/sample-360.mp4")) {
      server.files.put("/remote_control.php", read(input));
    }
    server.contentTypes.put("/remote_control.php", "video/mp4");
    DownloadStore.Job job = job("/remote_control.php");
    job.title = "网页标题-" + job.id;
    String expected = "原始+文件-" + job.id + ".mp4";
    server.dispositions.put(
        "/remote_control.php",
        "attachment; filename=fallback.mp4; filename*=UTF-8''"
            + "%E5%8E%9F%E5%A7%8B%2B%E6%96%87%E4%BB%B6-"
            + job.id
            + ".mp4");
    runNamed(job);
    assertPublishedName(job, expected);
  }

  @Test
  public void hlsNamesMp4FromEntryResponseNeverFromMediaSegment() throws Exception {
    server.files.put("/remote_control.php", server.files.get("/vod.m3u8"));
    server.contentTypes.put("/remote_control.php", "application/vnd.apple.mpegurl");
    DownloadStore.Job job = job("/remote_control.php");
    String base = "entry-playlist-" + job.id;
    server.dispositions.put("/remote_control.php", "attachment; filename=" + base + ".m3u8");
    server.dispositions.put("/segment00.ts", "attachment; filename=wrong-segment-name.mp4");
    runNamed(job);
    assertPublishedName(job, base + ".mp4");
    assertMp4(job, true);
  }

  private void runNamed(DownloadStore.Job job) throws Exception {
    new DownloadEngine(context, job, current -> {}).run();
    outputs.add(Uri.parse(job.outputUri));
    assertEquals("completed", job.state);
  }

  private void assertPublishedName(DownloadStore.Job job, String expected) {
    assertEquals(expected, job.fileName);
    try (android.database.Cursor row =
        context
            .getContentResolver()
            .query(
                Uri.parse(job.outputUri),
                new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
      assertNotNull(row);
      assertTrue(row.moveToFirst());
      assertEquals(expected, row.getString(0));
    }
  }

  @Test
  public void cancellationDoesNotPublishOutput() throws Exception {
    DownloadStore.Job job = job("/slow");
    DownloadEngine[] holder = new DownloadEngine[1];
    holder[0] =
        new DownloadEngine(
            context,
            job,
            current -> {
              if (current.bytes > 0) holder[0].cancel();
            });
    try {
      holder[0].run();
      fail("Expected cancellation");
    } catch (CancellationException expected) {
      assertEquals("", job.outputUri);
    }
  }

  @Test
  public void rejectsLivePlaylistAndNeverPublishes() throws Exception {
    DownloadStore.Job job = job("/live.m3u8");
    try {
      new DownloadEngine(context, job, current -> {}).run();
      fail("Expected live rejection");
    } catch (java.io.IOException expected) {
      assertTrue(expected.getMessage().contains("直播"));
      assertEquals("", job.outputUri);
    }
  }

  @Test
  public void foregroundQueuePersistsAndCompletesBothJobs() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      DownloadStore.Job first = job("/master.m3u8"), second = job("/document");
      String a = DownloadService.enqueue(context, first.url, first.title, first.headers);
      String b = DownloadService.enqueue(context, second.url, second.title, second.headers);
      storedIds.add(a);
      storedIds.add(b);
      DownloadStore.Job savedA = waitFor(a, "completed"), savedB = waitFor(b, "completed");
      outputs.add(Uri.parse(savedA.outputUri));
      outputs.add(Uri.parse(savedB.outputUri));
      assertMp4(savedA, true);
      assertEquals("测试文档.pdf", savedB.fileName);
      assertTrue(DownloadStore.list(context).stream().anyMatch(j -> a.equals(j.id)));
    }
  }

  @Test
  public void failedForegroundJobCanRetryWithoutDuplicatingRecord() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      DownloadStore.Job spec = job("/retry");
      String id = DownloadService.enqueue(context, spec.url, spec.title, spec.headers);
      storedIds.add(id);
      DownloadStore.Job failed = waitFor(id, "failed");
      assertTrue(failed.error.contains("503"));
      java.util.concurrent.CountDownLatch accepted = new java.util.concurrent.CountDownLatch(1);
      android.content.BroadcastReceiver receiver =
          new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context receivedContext, android.content.Intent intent) {
              if (id.equals(intent.getStringExtra(DownloadService.EXTRA_ID))
                  && "queued".equals(intent.getStringExtra(DownloadService.EXTRA_STATE)))
                accepted.countDown();
            }
          };
      androidx.core.content.ContextCompat.registerReceiver(
          context,
          receiver,
          new android.content.IntentFilter(DownloadService.ACTION_CHANGED),
          androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
      try {
        // Retry is accepted on the service main thread, after any previous run's cleanup.
        // Do not interpret the old HTTP 503 record as a failure of the new attempt.
        DownloadService.retry(context, id);
        assertTrue(
            "Retry did not acknowledge a queued task",
            accepted.await(5, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        context.unregisterReceiver(receiver);
      }
      DownloadStore.Job completed = waitFor(id, "completed");
      outputs.add(Uri.parse(completed.outputUri));
      assertEquals("Retry must make exactly one additional request", 2, server.retryRequests.get());
      assertEquals(1L, DownloadStore.list(context).stream().filter(j -> id.equals(j.id)).count());
    }
  }

  @Test
  public void crossOriginRedirectDoesNotLeakCookieOrAuthorization() throws Exception {
    DownloadStore.Job spec = job("/cross-redirect");
    spec.headers.put("Authorization", "Bearer private-fixture-token");
    new DownloadEngine(context, spec, current -> {}).run();
    outputs.add(Uri.parse(spec.outputUri));
    assertFalse(server.crossHeaders.containsKey("cookie"));
    assertFalse(server.crossHeaders.containsKey("authorization"));
    assertEquals("WebSurf-test", server.crossHeaders.get("user-agent"));
  }

  @Test
  public void hlsSegmentsTransferConcurrentlyButOutputRetainsOrder() throws Exception {
    DownloadStore.Job job = download("/parallel.m3u8");
    assertMp4(job, true);
    assertTrue("Segment transfers never overlapped", server.maxHls.get() >= 2);
    assertTrue("Segment concurrency exceeded its bound", server.maxHls.get() <= 4);
    assertEquals(4, job.totalParts);
    assertEquals(4, job.completedParts);
    assertTrue(
        "Fixture must complete later source segments first",
        !server.finishedHls.get(0).contains("segment00"));
  }

  @Test
  public void validatedHttpRangesTransferConcurrentlyAndPreserveEveryByte() throws Exception {
    DownloadStore.Job job = download("/range-large");
    assertTrue("HTTP ranges never overlapped", server.maxRanges.get() >= 2);
    assertTrue(server.maxRanges.get() <= 4);
    assertTrue(server.rangeRequests.get() >= 4);
    assertOutput(job, server.large);
    assertEquals(server.large.length, job.bytes);
  }

  @Test
  public void ignoredRangesFallBackToOneCompleteResponse() throws Exception {
    DownloadStore.Job job = download("/range-ignore");
    assertTrue(server.rangeRequests.get() > 0);
    assertTrue(server.fullRequests.get() >= 2);
    assertOutput(job, server.large);
    assertEquals(server.large.length, job.bytes);
  }

  @Test
  public void changedEntityNeverMixesOldAndNewRangeBytes() throws Exception {
    DownloadStore.Job job = download("/range-changed");
    assertTrue(server.rangeRequests.get() > 0);
    assertTrue(server.fullRequests.get() >= 2);
    assertOutput(job, server.changed);
    assertEquals(server.changed.length, job.bytes);
  }

  @Test
  public void incorrectRangeTotalFallsBackWithoutPublishingCorruption() throws Exception {
    DownloadStore.Job job = download("/range-wrong-total");
    assertTrue(server.rangeRequests.get() > 0);
    assertOutput(job, server.large);
    assertEquals(server.large.length, job.bytes);
  }

  @Test
  public void weakValidatorKeepsOneConnectionInsteadOfUnsafeRangeMixing() throws Exception {
    assertOutput(download("/range-weak"), server.large);
    assertEquals(0, server.rangeRequests.get());
  }

  @Test
  public void cancelledConcurrentHlsStopsEveryWorkerAndPublishesNothing() throws Exception {
    DownloadStore.Job job = job("/parallel.m3u8");
    DownloadEngine[] holder = new DownloadEngine[1];
    holder[0] =
        new DownloadEngine(
            context,
            job,
            current -> {
              if (current.bytes > 0) holder[0].cancel();
            });
    try {
      holder[0].run();
      fail("Expected cancellation");
    } catch (CancellationException expected) {
      assertEquals("", job.outputUri);
      assertEquals(0, job.connections);
      assertTrue(server.maxHls.get() >= 2);
    }
  }

  @Test
  public void singleConnectionPreferenceDisablesSegmentParallelism() throws Exception {
    assertTrue(
        context
            .getSharedPreferences("browser", Context.MODE_PRIVATE)
            .edit()
            .putInt("download_threads", 1)
            .commit());
    DownloadStore.Job job = download("/parallel.m3u8");
    assertMp4(job, true);
    assertEquals(1, server.maxHls.get());
    assertEquals(4, job.completedParts);
  }

  @Test
  public void lateProgressCannotReviveEndedOrRemovedTask() {
    DownloadStore.Job stale = job("/document");
    storedIds.add(stale.id);
    for (String terminal : new String[] {"failed", "cancelled", "completed"}) {
      stale.state = "downloading";
      stale.connections = 4;
      stale.bytesPerSecond = 4096;
      DownloadStore.put(context, stale);
      DownloadStore.changeState(context, stale.id, terminal, "terminal fixture");
      assertFalse(DownloadStore.updateProgress(context, stale));
      DownloadStore.Job saved = DownloadStore.get(context, stale.id);
      assertEquals(terminal, saved.state);
      assertEquals(0, saved.connections);
      assertEquals(0, saved.bytesPerSecond);
    }
    DownloadStore.remove(context, stale.id);
    assertFalse(DownloadStore.updateProgress(context, stale));
    assertNull(DownloadStore.get(context, stale.id));
  }

  @Test
  public void eightAndSixteenHlsWorkersReachTheirBoundAndRetainMediaOrder() throws Exception {
    for (int count : new int[] {8, 16}) {
      setThreads(count);
      server.maxHls.set(0);
      DownloadStore.Job job = download("/wide-" + count + ".m3u8");
      assertMp4(job, true);
      assertEquals("HLS overlap at configured " + count + " workers", count, server.maxHls.get());
      assertEquals(16, job.totalParts);
      assertEquals(16, job.completedParts);
    }
  }

  @Test
  public void eightAndSixteenHttpRangesReachTheirBoundAndPreserveBytes() throws Exception {
    for (int count : new int[] {8, 16}) {
      setThreads(count);
      server.maxRanges.set(0);
      server.rangeRequests.set(0);
      DownloadStore.Job job = download("/range-wide-" + count);
      assertEquals(
          "HTTP overlap at configured " + count + " workers", count, server.maxRanges.get());
      assertEquals(count, server.rangeRequests.get());
      assertOutput(job, server.wide);
      assertEquals(server.wide.length, job.bytes);
    }
  }

  @Test
  public void sixteenWorkerCancellationClosesAllTransfersWithoutOutput() throws Exception {
    setThreads(16);
    DownloadStore.Job job = job("/wide-16.m3u8");
    DownloadEngine[] holder = new DownloadEngine[1];
    holder[0] =
        new DownloadEngine(
            context,
            job,
            current -> {
              if (current.bytes > 0) holder[0].cancel();
            });
    try {
      holder[0].run();
      fail("Expected cancellation");
    } catch (CancellationException expected) {
      assertEquals("", job.outputUri);
      assertEquals(0, job.connections);
      assertEquals(16, server.maxHls.get());
    }
  }

  @Test
  public void smallHttpFilesUseOnlyEnoughRangesForHalfMegabyteParts() throws Exception {
    setThreads(16);
    DownloadStore.Job job = download("/range-large");
    assertOutput(job, server.large);
    assertEquals(6, server.rangeRequests.get());
    assertTrue(server.maxRanges.get() <= 6);
  }

  private void setThreads(int count) {
    assertTrue(
        context
            .getSharedPreferences("browser", Context.MODE_PRIVATE)
            .edit()
            .putInt("download_threads", count)
            .commit());
  }

  private void assertOutput(DownloadStore.Job job, byte[] expected) throws Exception {
    try (InputStream input =
        context.getContentResolver().openInputStream(Uri.parse(job.outputUri))) {
      assertArrayEquals(expected, read(input));
    }
  }

  private DownloadStore.Job waitFor(String id, String expected) throws Exception {
    long until = System.currentTimeMillis() + 20000;
    DownloadStore.Job job;
    do {
      job = DownloadStore.get(context, id);
      if (expected.equals(job.state)) return job;
      if ("failed".equals(job.state) && !"failed".equals(expected)) fail(job.error);
      Thread.sleep(100);
    } while (System.currentTimeMillis() < until);
    fail("Expected " + expected + " but was " + job.state + ": " + job.error);
    return job;
  }

  private DownloadStore.Job job(String path) {
    DownloadStore.Job j = new DownloadStore.Job();
    j.id = UUID.randomUUID().toString();
    j.title = "WebSurf integration " + j.id;
    j.url = server.base() + path;
    j.headers.put("Referer", "https://example.test/watch");
    j.headers.put("User-Agent", "WebSurf-test");
    j.headers.put("Cookie", "fixture=authorized");
    return j;
  }

  private DownloadStore.Job download(String path) throws Exception {
    DownloadStore.Job j = job(path);
    new DownloadEngine(context, j, current -> {}).run();
    assertEquals("completed", j.state);
    assertEquals(100, j.progress);
    outputs.add(Uri.parse(j.outputUri));
    return j;
  }

  private void assertMp4(DownloadStore.Job job, boolean audioExpected) throws Exception {
    assertEquals("video/mp4", job.mimeType);
    assertTrue(job.fileName.endsWith(".mp4"));
    try (InputStream stream =
        context.getContentResolver().openInputStream(Uri.parse(job.outputUri))) {
      byte[] head = new byte[12];
      assertEquals(12, stream.read(head));
      assertEquals("ftyp", new String(head, 4, 4, StandardCharsets.US_ASCII));
    }
    MediaExtractor extractor = new MediaExtractor();
    try {
      extractor.setDataSource(context, Uri.parse(job.outputUri), null);
      int video = 0, audio = 0;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime.startsWith("video/")) {
          video++;
          assertEquals(320, format.getInteger(MediaFormat.KEY_WIDTH));
          assertTrue(
              "Output must retain the complete clip",
              format.getLong(MediaFormat.KEY_DURATION) >= 3_800_000);
          extractor.selectTrack(i);
          int samples = 0;
          long last = -1;
          while (extractor.getSampleTrackIndex() >= 0) {
            samples++;
            last = extractor.getSampleTime();
            extractor.advance();
          }
          assertTrue(samples >= 78);
          assertTrue(last >= 3_800_000);
          extractor.unselectTrack(i);
        }
        if (mime.startsWith("audio/")) audio++;
      }
      assertEquals(1, video);
      if (audioExpected) assertEquals(1, audio);
    } finally {
      extractor.release();
    }
  }

  private static byte[] read(InputStream input) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = input.read(b)) != -1) out.write(b, 0, n);
    return out.toByteArray();
  }

  private static final class FixtureServer implements AutoCloseable {
    final ServerSocket socket = new ServerSocket(0);
    final ExecutorService threads = Executors.newCachedThreadPool();
    final Map<String, byte[]> files = new HashMap<>();
    final Map<String, String> contentTypes = new java.util.concurrent.ConcurrentHashMap<>();
    final Map<String, String> dispositions = new java.util.concurrent.ConcurrentHashMap<>();
    final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger rejected = new AtomicInteger();
    final AtomicInteger retryRequests = new AtomicInteger();
    final AtomicInteger rangeRequests = new AtomicInteger(), fullRequests = new AtomicInteger();
    final AtomicInteger activeRanges = new AtomicInteger(), maxRanges = new AtomicInteger();
    final AtomicInteger activeHls = new AtomicInteger(), maxHls = new AtomicInteger();
    final List<String> finishedHls = java.util.Collections.synchronizedList(new ArrayList<>());
    final byte[] large = new byte[3 * 1024 * 1024], changed = new byte[3 * 1024 * 1024];
    final byte[] wide = new byte[8 * 1024 * 1024];
    final java.util.concurrent.CountDownLatch wideHls8 = new java.util.concurrent.CountDownLatch(8);
    final java.util.concurrent.CountDownLatch wideHls16 =
        new java.util.concurrent.CountDownLatch(16);
    final java.util.concurrent.CountDownLatch wideRange8 =
        new java.util.concurrent.CountDownLatch(8);
    final java.util.concurrent.CountDownLatch wideRange16 =
        new java.util.concurrent.CountDownLatch(16);
    volatile Map<String, String> crossHeaders = new HashMap<>();
    final byte[] pdf =
        "%PDF-1.4\nWebSurf byte-preservation fixture\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
    volatile boolean closed;

    FixtureServer() throws Exception {
      Context test = InstrumentationRegistry.getInstrumentation().getContext();
      for (String name : test.getAssets().list("download_fixture")) {
        try (InputStream in = test.getAssets().open("download_fixture/" + name)) {
          files.put("/" + name, read(in));
        }
      }
      files.put(
          "/master.m3u8",
          ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100,RESOLUTION=160x90\nbad-low.m3u8\n"
                  + "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=320x180\nvod.m3u8\n")
              .getBytes(StandardCharsets.UTF_8));
      files.put(
          "/separate.m3u8",
          ("#EXTM3U\n"
               + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"main\",NAME=\"Audio\",DEFAULT=YES,URI=\"audio.m3u8\"\n"
               + "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=320x180,AUDIO=\"main\"\n"
               + "video.m3u8\n")
              .getBytes(StandardCharsets.UTF_8));
      for (int i = 0; i < large.length; i++) {
        large[i] = (byte) (i * 31 + i / 65536);
        changed[i] = (byte) ~large[i];
      }
      for (int i = 0; i < wide.length; i++) wide[i] = (byte) (i * 37 + i / 8192);
      String vod = new String(files.get("/vod.m3u8"), StandardCharsets.UTF_8);
      files.put(
          "/parallel.m3u8",
          vod.replace("segment", "delayed-segment").getBytes(StandardCharsets.UTF_8));
      byte[] key = new byte[16];
      Arrays.fill(key, (byte) 42);
      files.put("/key.bin", key);
      String aes =
          vod.replace(
              "#EXT-X-MEDIA-SEQUENCE:0",
              "#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"");
      ByteArrayOutputStream joined = new ByteArrayOutputStream();
      String ranges = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n";
      for (int i = 0; i < 4; i++) {
        String name = String.format(java.util.Locale.ROOT, "segment%02d.ts", i);
        byte[] plain = files.get("/" + name);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
            Cipher.ENCRYPT_MODE,
            new SecretKeySpec(key, "AES"),
            new IvParameterSpec(DownloadEngine.iv("", i + 7)));
        files.put("/enc-" + name, cipher.doFinal(plain));
        aes = aes.replace(name, "enc-" + name);
        ranges +=
            "#EXTINF:1,\n#EXT-X-BYTERANGE:"
                + plain.length
                + "@"
                + joined.size()
                + "\ncombined.ts\n";
        joined.write(plain);
      }
      files.put("/aes.m3u8", aes.getBytes(StandardCharsets.UTF_8));
      files.put("/ranges.m3u8", (ranges + "#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8));
      files.put("/combined.ts", joined.toByteArray());
      byte[] allSegments = joined.toByteArray();
      int packets = allSegments.length / 188;
      for (int concurrency : new int[] {8, 16}) {
        StringBuilder widePlaylist =
            new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n");
        for (int i = 0; i < 16; i++) {
          int from = (packets * i / 16) * 188;
          int to = i == 15 ? allSegments.length : (packets * (i + 1) / 16) * 188;
          String part =
              String.format(java.util.Locale.ROOT, "wide-segment-%d-%02d.ts", concurrency, i);
          files.put("/" + part, Arrays.copyOfRange(allSegments, from, to));
          widePlaylist.append("#EXTINF:0.25,\n").append(part).append('\n');
        }
        widePlaylist.append("#EXT-X-ENDLIST\n");
        files.put(
            "/wide-" + concurrency + ".m3u8",
            widePlaylist.toString().getBytes(StandardCharsets.UTF_8));
      }
      files.put("/live.m3u8", vod.replace("#EXT-X-ENDLIST", "").getBytes(StandardCharsets.UTF_8));
      files.put("/document", pdf);
      threads.execute(
          () -> {
            while (!closed) {
              try {
                Socket client = socket.accept();
                threads.execute(() -> serve(client));
              } catch (Exception e) {
                if (!closed) throw new RuntimeException(e);
              }
            }
          });
    }

    String base() {
      return "http://127.0.0.1:" + socket.getLocalPort();
    }

    private void serve(Socket client) {
      boolean countedRange = false, countedHls = false;
      try (Socket s = client) {
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
        String first = reader.readLine();
        if (first == null) return;
        String path = first.split(" ")[1];
        requests.add(path);
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
          int colon = line.indexOf(':');
          if (colon > 0)
            headers.put(
                line.substring(0, colon).toLowerCase(java.util.Locale.ROOT),
                line.substring(colon + 1).trim());
        }
        OutputStream out = s.getOutputStream();
        if (path.equals("/cross-redirect")) {
          out.write(
              ("HTTP/1.1 302 Found\r\nLocation: http://localhost:"
                      + socket.getLocalPort()
                      + "/cross-target\r\nContent-Length: 0\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
          return;
        }
        if (path.equals("/cross-target")) {
          crossHeaders = headers;
          out.write(
              ("HTTP/1.1 200 OK\r\nContent-Type: application/pdf\r\nContent-Length: "
                      + pdf.length
                      + "\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
          out.write(pdf);
          return;
        }
        if (!"fixture=authorized".equals(headers.get("cookie"))
            || !"https://example.test/watch".equals(headers.get("referer"))
            || !"WebSurf-test".equals(headers.get("user-agent"))) {
          rejected.incrementAndGet();
          out.write(
              "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
          return;
        }
        if (path.equals("/redirect")) {
          out.write(
              "HTTP/1.1 302 Found\r\nLocation: /master.m3u8\r\nContent-Length: 0\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
          return;
        }
        if (path.equals("/retry")) {
          if (retryRequests.getAndIncrement() == 0) {
            out.write(
                "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            return;
          }
          path = "/document";
        }
        if (path.equals("/slow")) {
          out.write(
              "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 10485760\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
          for (int i = 0; i < 160; i++) {
            out.write(new byte[65536]);
            out.flush();
            Thread.sleep(40);
          }
          return;
        }
        if (path.startsWith("/range-")) {
          boolean range = headers.containsKey("range");
          int full = range ? fullRequests.get() : fullRequests.incrementAndGet();
          if (range) {
            rangeRequests.incrementAndGet();
            countedRange = true;
            maxRanges.accumulateAndGet(activeRanges.incrementAndGet(), Math::max);
          }
          boolean change = path.equals("/range-changed") && (range || full > 1);
          byte[] entity = path.startsWith("/range-wide-") ? wide : change ? changed : large;
          byte[] payload = entity;
          String response = "HTTP/1.1 200 OK\r\n";
          if (range && !path.equals("/range-ignore")) {
            assertNotNull("Range transfer must be conditional", headers.get("if-range"));
            String[] bits = headers.get("range").substring(6).split("-");
            int start = Integer.parseInt(bits[0]), end = Integer.parseInt(bits[1]);
            payload = Arrays.copyOfRange(entity, start, end + 1);
            response =
                "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes "
                    + start
                    + "-"
                    + end
                    + "/"
                    + (entity.length + (path.equals("/range-wrong-total") ? 1 : 0))
                    + "\r\n";
          }
          String etag =
              path.equals("/range-weak") ? "W/\"fixture\"" : change ? "\"changed\"" : "\"fixture\"";
          response +=
              "Content-Type: application/octet-stream\r\nContent-Length: "
                  + payload.length
                  + "\r\nAccept-Ranges: bytes\r\nETag: "
                  + etag
                  + "\r\nConnection: close\r\n\r\n";
          out.write(response.getBytes(StandardCharsets.US_ASCII));
          out.flush();
          if (range) {
            if (path.startsWith("/range-wide-")) {
              java.util.concurrent.CountDownLatch gate =
                  path.endsWith("-16") ? wideRange16 : wideRange8;
              gate.countDown();
              if (!gate.await(8, java.util.concurrent.TimeUnit.SECONDS))
                throw new java.io.IOException("Range concurrency gate timed out");
            } else Thread.sleep(120);
            activeRanges.decrementAndGet();
            countedRange = false;
          }
          out.write(payload);
          out.flush();
          return;
        }
        String delayed = null;
        if (path.startsWith("/wide-segment-")) {
          countedHls = true;
          maxHls.accumulateAndGet(activeHls.incrementAndGet(), Math::max);
          java.util.concurrent.CountDownLatch gate =
              path.startsWith("/wide-segment-16-") ? wideHls16 : wideHls8;
          gate.countDown();
          if (!gate.await(8, java.util.concurrent.TimeUnit.SECONDS))
            throw new java.io.IOException("HLS concurrency gate timed out");
        }
        if (path.startsWith("/delayed-segment")) {
          delayed = path;
          countedHls = true;
          maxHls.accumulateAndGet(activeHls.incrementAndGet(), Math::max);
          Thread.sleep(path.contains("segment00") ? 400 : 80);
          path = path.replace("delayed-segment", "segment");
        }
        byte[] body = files.get(path);
        if (body == null) {
          out.write(
              "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
          return;
        }
        String response = "HTTP/1.1 200 OK\r\n";
        String range = headers.get("range");
        if (range != null) {
          String[] bits = range.substring(6).split("-");
          int start = Integer.parseInt(bits[0]), end = Integer.parseInt(bits[1]);
          response =
              "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes "
                  + start
                  + "-"
                  + end
                  + "/"
                  + body.length
                  + "\r\n";
          body = Arrays.copyOfRange(body, start, end + 1);
        }
        String type =
            path.endsWith(".m3u8")
                ? "application/vnd.apple.mpegurl"
                : path.equals("/document") ? "application/pdf" : "application/octet-stream";
        type = contentTypes.getOrDefault(path, type);
        response += "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n";
        if (dispositions.containsKey(path))
          response += "Content-Disposition: " + dispositions.get(path) + "\r\n";
        if (path.equals("/document"))
          response +=
              "Content-Disposition: attachment; filename=doc.pdf;"
                  + " filename*=UTF-8''%E6%B5%8B%E8%AF%95%E6%96%87%E6%A1%A3.pdf\r\n";
        out.write((response + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (countedHls) {
          activeHls.decrementAndGet();
          countedHls = false;
        }
        out.write(body);
        out.flush();
        if (delayed != null) finishedHls.add(delayed);
      } catch (Exception ignored) {
      } finally {
        if (countedRange) activeRanges.decrementAndGet();
        if (countedHls) activeHls.decrementAndGet();
      }
    }

    public void close() throws Exception {
      closed = true;
      socket.close();
      threads.shutdownNow();
    }
  }
}
