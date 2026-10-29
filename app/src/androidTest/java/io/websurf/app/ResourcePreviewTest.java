package io.websurf.app;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.webkit.CookieManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ResourcePreviewTest {
  private Fixture server;
  private ResourcePreviewLoader loader;
  private final Map<String, String> headers = new LinkedHashMap<>();

  @Before
  public void setup() throws Exception {
    server = new Fixture();
    headers.put("User-Agent", "WebSurf-preview-test");
    headers.put("Referer", server.base() + "/page");
    headers.put("Cookie", "preview_private=1");
    headers.put("Authorization", "Bearer preview-private");
    ui(
        () -> {
          loader = new ResourcePreviewLoader();
          CookieManager.getInstance().setCookie(server.base(), "preview_private=1; Path=/");
        });
  }

  @After
  public void cleanup() throws Exception {
    ui(
        () -> {
          if (loader != null) loader.close();
          if (server != null)
            CookieManager.getInstance()
                .setCookie(server.base(), "preview_private=; Max-Age=0; Path=/");
        });
    if (server != null) server.close();
  }

  @Test
  public void actualVideoFrameAndResolutionAreReadWithBoundedRanges() throws Exception {
    WebResource resource = new WebResource(server.base() + "/video.mp4");
    ResourcePreviewLoader.Result result = load(resource);
    assertNotNull(result.error, result.bitmap);
    assertEquals("视频画面", result.imageSource);
    assertEquals(1280, result.width);
    assertEquals(720, result.height);
    assertEquals("720p · 1280 × 720", resource.qualityLabel());
    Set<Integer> colors = new HashSet<>();
    for (int y = 0; y < result.bitmap.getHeight(); y += 8)
      for (int x = 0; x < result.bitmap.getWidth(); x += 8)
        colors.add(result.bitmap.getPixel(x, y));
    assertTrue("Expected an actual multicolored video frame", colors.size() > 10);
    assertTrue(server.transferred.get() > 0);
    assertTrue("Unbounded preview transfer", server.transferred.get() <= 6 * 1024 * 1024);
    assertEquals(0, server.authenticationFailures.get());
  }

  @Test
  public void threeSecondFrameSkipsBlackIntroWithoutSelectingAnEarlierKeyframe() throws Exception {
    WebResource resource = new WebResource(server.base() + "/black-intro.mp4");
    ResourcePreviewLoader.Result result = load(resource);
    assertNotNull(result.error, result.bitmap);
    assertEquals("视频画面", result.imageSource);
    assertEquals(5000, result.durationMs);
    int pixel = result.bitmap.getPixel(result.bitmap.getWidth() / 2, result.bitmap.getHeight() / 2);
    assertTrue(
        "The three-second non-keyframe must be red, not the black opening", Color.red(pixel) > 180);
    assertTrue(Color.green(pixel) < 60);
    assertTrue(Color.blue(pixel) < 60);
    assertTrue("Preview transfer must remain bounded", server.transferred.get() <= 6 * 1024 * 1024);
  }

  @Test
  public void shortVideoUsesItsFirstFrameInsteadOfSeekingPastItsEnd() throws Exception {
    WebResource resource = new WebResource(server.base() + "/short-video.mp4");
    ResourcePreviewLoader.Result result = load(resource);
    assertNotNull(result.error, result.bitmap);
    assertEquals("视频画面", result.imageSource);
    assertTrue(result.durationMs > 0 && result.durationMs < 3000);
    int pixel = result.bitmap.getPixel(result.bitmap.getWidth() / 2, result.bitmap.getHeight() / 2);
    assertTrue("The short clip must retain its blue first frame", Color.blue(pixel) > 180);
    assertTrue(Color.red(pixel) < 60);
    assertTrue(Color.green(pixel) < 60);
  }

  @Test
  public void hlsMasterUsesPosterAndLabelsMaximumWithoutFetchingSegments() throws Exception {
    WebResource resource = new WebResource(server.base() + "/master.m3u8");
    resource.posterUrl = server.base() + "/poster.png";
    ResourcePreviewLoader.Result result = load(resource);
    assertNotNull(result.bitmap);
    assertEquals("网页封面", result.imageSource);
    assertEquals("360p / 720p", resource.availableQualities);
    assertEquals("自适应 · 最高 720p", resource.qualityLabel());
    assertEquals(0, resource.width);
    assertEquals(0, resource.height);
    assertEquals(0, server.segmentRequests.get());
    assertEquals(2, server.requests.get());
  }

  @Test
  public void posterRedirectDoesNotForwardAnotherOriginCredentials() throws Exception {
    WebResource resource = new WebResource(server.base() + "/master.m3u8");
    resource.posterUrl = server.base() + "/poster-redirect";
    ResourcePreviewLoader.Result result = load(resource);
    assertNotNull(result.bitmap);
    assertEquals(1, server.crossRequests.get());
    assertEquals(0, server.credentialLeaks.get());
  }

  @Test
  public void renderedFrameSeedReplacesPosterAndKeepsPortraitDimensions() throws Exception {
    WebResource resource = new WebResource(server.base() + "/master.m3u8");
    resource.posterUrl = server.base() + "/poster.png";
    assertEquals("网页封面", load(resource).imageSource);
    Bitmap bitmap = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(Color.RED);
    ui(() -> loader.seed(resource, headers, bitmap, 720, 1280));
    bitmap.recycle();
    ResourcePreviewLoader.Result seeded = load(resource);
    assertEquals("播放画面", seeded.imageSource);
    assertEquals(Color.RED, seeded.bitmap.getPixel(1, 1));
    assertEquals("720p · 720 × 1280", resource.qualityLabel());
    assertEquals("360p / 720p", resource.availableQualities);
    assertEquals(2, server.requests.get());
  }

  @Test
  public void firstFrameSeedStillDiscoversHlsVariantsWithoutFetchingPosterOrSegments()
      throws Exception {
    WebResource resource = new WebResource(server.base() + "/master.m3u8");
    resource.posterUrl = server.base() + "/poster.png";
    Bitmap bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(Color.GREEN);
    ui(() -> loader.seed(resource, headers, bitmap, 640, 360));
    bitmap.recycle();
    CountDownLatch discovered = new CountDownLatch(1);
    AtomicInteger callbacks = new AtomicInteger();
    AtomicReference<ResourcePreviewLoader.Result> value = new AtomicReference<>();
    ui(
        () ->
            loader.load(
                resource,
                headers,
                result -> {
                  callbacks.incrementAndGet();
                  value.set(result);
                  if (!result.availableQualities.isEmpty()) discovered.countDown();
                }));
    assertTrue(discovered.await(10, TimeUnit.SECONDS));
    assertEquals(2, callbacks.get());
    assertEquals("播放画面", value.get().imageSource);
    assertEquals(Color.GREEN, value.get().bitmap.getPixel(1, 1));
    assertEquals("360p / 720p", resource.availableQualities);
    assertEquals("360p · 640 × 360 · 最高 720p", resource.qualityLabel());
    assertEquals(1, server.requests.get());
    assertEquals(0, server.segmentRequests.get());
  }

  @Test
  public void changedSourceRejectsPendingPosterAndMetadata() throws Exception {
    WebResource resource = new WebResource(server.base() + "/master.m3u8");
    resource.posterUrl = server.base() + "/delayed-poster";
    resource.previewIdentity = "document:video:1";
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<ResourcePreviewLoader.Result> value = new AtomicReference<>();
    ui(
        () ->
            loader.load(
                resource,
                headers,
                result -> {
                  value.set(result);
                  completed.countDown();
                }));
    assertTrue(server.delayedPosterRequested.await(5, TimeUnit.SECONDS));
    // The URL and poster can both be reused by a site's next video. Generation still differs.
    ui(() -> resource.previewIdentity = "document:video:2");
    server.delayedPosterRelease.countDown();
    assertTrue(completed.await(10, TimeUnit.SECONDS));
    assertNull(value.get().bitmap);
    assertEquals("资源已更新，请重试", value.get().error);
    assertEquals("", resource.availableQualities);
    assertEquals(0, resource.maxVariantWidth);
    assertEquals(0, resource.previewRevision);
  }

  @Test
  public void cachedFrameDoesNotRevertNewerNativeResolution() throws Exception {
    WebResource resource = new WebResource(server.base() + "/video.mp4");
    Bitmap bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
    ui(() -> loader.seed(resource, headers, bitmap, 640, 360));
    bitmap.recycle();
    ui(
        () -> {
          resource.width = 1920;
          resource.height = 1080;
          resource.qualitySource = "播放画面";
        });
    assertNotNull(load(resource).bitmap);
    assertEquals("1080p · 1920 × 1080", resource.qualityLabel());
    assertEquals(0, server.requests.get());
  }

  private ResourcePreviewLoader.Result load(WebResource resource) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<ResourcePreviewLoader.Result> value = new AtomicReference<>();
    ui(
        () ->
            loader.load(
                resource,
                headers,
                result -> {
                  value.set(result);
                  done.countDown();
                }));
    assertTrue("Preview callback exceeded its bounded work", done.await(18, TimeUnit.SECONDS));
    return value.get();
  }

  private static void ui(Runnable action) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }

  private static final class Fixture implements AutoCloseable {
    final ServerSocket socket = new ServerSocket(0);
    final ExecutorService pool = Executors.newCachedThreadPool();
    final AtomicInteger requests = new AtomicInteger(),
        transferred = new AtomicInteger(),
        segmentRequests = new AtomicInteger(),
        crossRequests = new AtomicInteger(),
        credentialLeaks = new AtomicInteger(),
        authenticationFailures = new AtomicInteger();
    final byte[] video, blackIntro, shortVideo, poster, master;
    final CountDownLatch delayedPosterRequested = new CountDownLatch(1),
        delayedPosterRelease = new CountDownLatch(1);
    volatile boolean closed;

    Fixture() throws Exception {
      video = asset("fixtures/media/sample-720.mp4");
      blackIntro = asset("fixtures/media/preview-black-intro.mp4");
      shortVideo = asset("fixtures/media/preview-short.mp4");
      poster = asset("fixtures/media/cover.png");
      master =
          ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=10,RESOLUTION=640x360\nlow.m3u8\n"
                  + "#EXT-X-STREAM-INF:BANDWIDTH=20,RESOLUTION=1280x720\nhigh.m3u8\n")
              .getBytes(StandardCharsets.UTF_8);
      pool.execute(
          () -> {
            while (!closed)
              try {
                Socket client = socket.accept();
                pool.execute(() -> serve(client));
              } catch (IOException failure) {
                if (!closed) throw new RuntimeException(failure);
              }
          });
    }

    String base() {
      return "http://127.0.0.1:" + socket.getLocalPort();
    }

    byte[] asset(String name) throws IOException {
      try (InputStream in =
              InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
      }
    }

    void serve(Socket client) {
      try (Socket connection = client) {
        connection.setSoTimeout(5000);
        BufferedReader input =
            new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
        String first = input.readLine();
        if (first == null) return;
        String path = first.split(" ")[1];
        requests.incrementAndGet();
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = input.readLine()) != null && !line.isEmpty()) {
          int colon = line.indexOf(':');
          if (colon > 0)
            headers.put(
                line.substring(0, colon).toLowerCase(Locale.ROOT),
                line.substring(colon + 1).trim());
        }
        OutputStream output = connection.getOutputStream();
        if (path.equals("/delayed-poster")) {
          delayedPosterRequested.countDown();
          try {
            if (!delayedPosterRelease.await(10, TimeUnit.SECONDS)) return;
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          }
        }
        if (path.equals("/poster-redirect")) {
          output.write(
              ("HTTP/1.1 302 Found\r\nLocation: http://localhost:"
                      + socket.getLocalPort()
                      + "/cross-poster\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
          return;
        }
        if (path.equals("/cross-poster")) {
          crossRequests.incrementAndGet();
          if (headers.getOrDefault("cookie", "").contains("preview_private")
              || headers.containsKey("authorization")) credentialLeaks.incrementAndGet();
        }
        if (path.equals("/video.mp4")
            && (!headers.getOrDefault("cookie", "").contains("preview_private=1")
                || !headers.getOrDefault("authorization", "").equals("Bearer preview-private")
                || !headers.getOrDefault("referer", "").equals(base() + "/page"))) {
          authenticationFailures.incrementAndGet();
          output.write(
              "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
          return;
        }
        byte[] data =
            path.equals("/video.mp4")
                ? video
                : path.equals("/black-intro.mp4")
                    ? blackIntro
                    : path.equals("/short-video.mp4")
                        ? shortVideo
                        : path.equals("/master.m3u8") ? master : poster;
        if (path.endsWith(".ts") || path.equals("/low.m3u8") || path.equals("/high.m3u8"))
          segmentRequests.incrementAndGet();
        int start = 0, end = data.length - 1;
        String range = headers.get("range");
        boolean partial = range != null && range.startsWith("bytes=");
        if (partial) {
          String[] pieces = range.substring(6).split("-", 2);
          start = Integer.parseInt(pieces[0]);
          if (pieces.length > 1 && !pieces[1].isEmpty())
            end = Math.min(end, Integer.parseInt(pieces[1]));
        }
        if (start > end) {
          output.write(
              "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
          return;
        }
        int length = end - start + 1;
        String response =
            "HTTP/1.1 "
                + (partial ? "206 Partial Content" : "200 OK")
                + "\r\nContent-Length: "
                + length
                + "\r\nConnection: close\r\n"
                + (partial
                    ? "Content-Range: bytes " + start + "-" + end + "/" + data.length + "\r\n"
                    : "")
                + "\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.write(data, start, length);
        output.flush();
        transferred.addAndGet(length);
      } catch (IOException ignored) {
      }
    }

    @Override
    public void close() throws IOException {
      closed = true;
      delayedPosterRelease.countDown();
      socket.close();
      pool.shutdownNow();
    }
  }
}
