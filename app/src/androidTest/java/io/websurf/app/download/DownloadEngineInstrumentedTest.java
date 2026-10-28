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
  private final List<Uri> outputs = new ArrayList<>();
  private final List<String> storedIds = new ArrayList<>();

  @Before
  public void setup() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    server = new FixtureServer();
  }

  @After
  public void cleanup() throws Exception {
    if (server != null) server.close();
    for (Uri uri : outputs) context.getContentResolver().delete(uri, null, null);
    for (String id : storedIds) DownloadStore.remove(context, id);
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
      DownloadService.retry(context, id);
      DownloadStore.Job completed = waitFor(id, "completed");
      outputs.add(Uri.parse(completed.outputUri));
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
    final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger rejected = new AtomicInteger();
    final AtomicInteger retryRequests = new AtomicInteger();
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
      String vod = new String(files.get("/vod.m3u8"), StandardCharsets.UTF_8);
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
        response += "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n";
        if (path.equals("/document"))
          response +=
              "Content-Disposition: attachment; filename=doc.pdf;"
                  + " filename*=UTF-8''%E6%B5%8B%E8%AF%95%E6%96%87%E6%A1%A3.pdf\r\n";
        out.write((response + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
      } catch (Exception ignored) {
      }
    }

    public void close() throws Exception {
      closed = true;
      socket.close();
      threads.shutdownNow();
    }
  }
}
