package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import io.websurf.app.download.DownloadService;
import io.websurf.app.download.DownloadStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.*;

/** Synthetic export links, browser attachment handling and native playback. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class DirectMediaLinkTest {
  private Context context;
  private ActivityScenario<MainActivity> scenario;
  private Fixture fixture;
  private final List<String> jobs = new ArrayList<>();
  private boolean hadAuto, oldAuto;

  @Before
  public void open() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var prefs = context.getSharedPreferences("browser", 0);
    hadAuto = prefs.contains("auto_play");
    oldAuto = prefs.getBoolean("auto_play", true);
    prefs.edit().putBoolean("auto_play", false).commit();
    fixture = new Fixture();
    scenario = FixtureHttp.launchHomepage(context, fixture.page());
    await(
        "Page and direct media link were not observed",
        () -> ui(a -> a.current.resources.containsKey(fixture.media() + "/export")));
  }

  @After
  public void close() throws Exception {
    if (scenario != null) scenario.close();
    for (String id : jobs) {
      DownloadStore.Job job = DownloadStore.get(context, id);
      if (job != null && !job.outputUri.isBlank())
        context.getContentResolver().delete(Uri.parse(job.outputUri), null, null);
      DownloadService.cancel(context, id);
      DownloadStore.remove(context, id);
    }
    if (context != null) {
      var edit = context.getSharedPreferences("browser", 0).edit();
      if (hadAuto) edit.putBoolean("auto_play", oldAuto);
      else edit.remove("auto_play");
      edit.commit();
    }
    if (fixture != null) fixture.close();
  }

  @Test
  public void unrequestedExportLinkCanPlayAndDownloadWithoutInventingAReferrer() throws Exception {
    String page = ui(a -> a.current.web.getUrl());
    WebResource resource = ui(a -> a.current.resources.get(fixture.media() + "/export"));
    ui(
        a -> {
          assertTrue(a.current.videos.isEmpty());
          assertTrue(a.takeoverButton.isEnabled());
          assertEquals("播放视频", a.takeoverButton.getText().toString());
          assertFalse(a.headersFor(resource).containsKey("Referer"));
          a.takeoverButton.performClick();
          return null;
        });
    awaitPlaying();
    assertEquals(page, ui(a -> a.current.web.getUrl()));
    assertNull(ui(a -> a.activeTarget));
    ui(
        a -> {
          a.closePlayer(false, false);
          return null;
        });
    String id =
        ui(
            a ->
                DownloadService.enqueue(
                    a, resource.url, "Export fixture", a.mediaHeadersFor(resource), resource.mime));
    jobs.add(id);
    assertDownloaded(id);
    assertEquals(0, fixture.rejected.get());
  }

  @Test
  public void unrequestedCrossOriginExportHasNoInferredReferrer() throws Exception {
    String url = "https://cdn.example/export.mp4";
    WebResource resource = observeLink(url);
    ui(
        a -> {
          assertFalse(resource.requestHeadersCaptured);
          assertFalse(a.headersFor(resource).containsKey("Referer"));
          return null;
        });
  }

  @Test
  public void nativePlaybackBlocksRedirectAndHlsSegmentOnOtherLoopbackPort() throws Exception {
    try (FixtureHttp.Canary privateService = new FixtureHttp.Canary()) {
      fixture.privateTarget = privateService.base() + "/private.ts";
      for (String path : List.of("/restricted.m3u8", "/restricted-redirect.mp4")) {
        WebResource resource = observeLink(fixture.media() + path);
        CompletableFuture<Throwable> failed = new CompletableFuture<>();
        ui(
            a -> {
              assertEquals(
                  fixture.media() + "/",
                  a.mediaHeadersFor(resource).get(NativeMediaHttp.LOCAL_ORIGIN));
              a.startPlayer(resource, 0, null, VideoChrome.Mode.FLOATING, true);
              a.player.addListener(
                  new androidx.media3.common.Player.Listener() {
                    @Override
                    public void onPlayerError(androidx.media3.common.PlaybackException error) {
                      failed.complete(error);
                    }
                  });
              return null;
            });
        Throwable failure = failed.get(20, TimeUnit.SECONDS);
        Throwable cause = failure;
        while (cause != null && !"拒绝跨来源请求局域网媒体".equals(cause.getMessage())) {
          cause = cause.getCause();
        }
        assertNotNull(path + ": " + android.util.Log.getStackTraceString(failure), cause);
        assertTrue(
            fixture.hits.toString(),
            fixture.hits.stream().anyMatch(h -> path.equals(h.path()) && !h.browser()));
        assertEquals(path, 0, privateService.connections.get());
      }
    }
  }

  private WebResource observeLink(String url) throws Exception {
    ui(
        a -> {
          a.current.web.evaluateJavascript(
              "var link=document.createElement('a');link.href="
                  + org.json.JSONObject.quote(url)
                  + ";link.type='video/mp4';link.textContent='Export';document.body.append(link)",
              null);
          return null;
        });
    await("Export link was not discovered", () -> ui(a -> a.current.resources.containsKey(url)));
    return ui(a -> a.current.resources.get(url));
  }

  @Test
  public void typingExactLanAttachmentAuthorizesOnlyTheNativeAction() throws Exception {
    String clip = fixture.media() + "/clip.mp4";
    ui(
        a -> {
          a.newTab("about:blank", true);
          return null;
        });
    await("Empty tab did not open", () -> ui(a -> "about:blank".equals(a.current.web.getUrl())));
    ui(
        a -> {
          a.navigate(clip);
          return null;
        });
    tapResourceAction("播放视频");
    awaitPlaying();
    String job =
        ui(
            a -> {
              WebResource chosen = a.playing;
              assertEquals(clip, chosen.url);
              assertTrue(a.headersFor(chosen).containsKey(NativeMediaHttp.LOCAL_ORIGIN));
              WebResource row = a.current.resources.get(clip);
              assertNotSame(chosen, row);
              if (row != null)
                assertFalse(a.headersFor(row).containsKey(NativeMediaHttp.LOCAL_ORIGIN));
              return DownloadService.enqueue(
                  a, clip, "LAN attachment", a.mediaHeadersFor(chosen), chosen.mime);
            });
    jobs.add(job);
    assertDownloaded(job);
  }

  @Test
  public void browserAttachmentRedirectPreservesAbsentReferrerAndOffersPlayback() throws Exception {
    // A trusted touch follows the browser's HTTP Referrer-Policy and invokes DownloadListener.
    tapExport();
    await(
        "Browser did not report the final attachment",
        () ->
            ui(
                a -> {
                  WebResource resource = a.current.resources.get(fixture.media() + "/clip.mp4");
                  return resource != null && resource.requestHeadersCaptured;
                }));
    tapResourceAction("播放视频");
    awaitPlaying();
    WebResource resource = ui(a -> a.playing);
    assertTrue(ui(a -> resource.requestHeadersCaptured));
    assertTrue(
        ui(
            a ->
                a.headersFor(resource).keySet().stream()
                    .noneMatch(k -> k.equalsIgnoreCase("Referer"))));
    ui(
        a -> {
          a.closePlayer(false, false);
          return null;
        });
    String id =
        ui(
            a ->
                DownloadService.enqueue(
                    a,
                    resource.url,
                    "Attachment fixture",
                    a.mediaHeadersFor(resource),
                    resource.mime));
    jobs.add(id);
    assertDownloaded(id);
    var hits = List.copyOf(fixture.hits);
    Hit navigation =
        hits.stream()
            .filter(h -> h.path().equals("/export") && h.browser() && h.status().startsWith("302"))
            .findFirst()
            .orElseThrow();
    assertFalse(navigation.agent().isBlank());
    assertTrue(
        hits.toString(),
        hits.stream()
            .anyMatch(
                h -> h.path().equals("/clip.mp4") && h.browser() && h.status().startsWith("200")));
    assertTrue(
        hits.toString(),
        hits.stream()
            .anyMatch(
                h ->
                    h.path().equals("/clip.mp4")
                        && !h.browser()
                        && h.status().startsWith("2")
                        && h.agent().equals(navigation.agent())));
    assertTrue(hits.toString(), hits.stream().allMatch(h -> h.referrer().isEmpty()));
    assertEquals(0, fixture.rejected.get());
  }

  @Test
  public void capturedHeadersKeepCaseInsensitiveReferrerAndOriginAbsenceAuthoritative() {
    ui(
        a -> {
          WebResource resource = new WebResource(fixture.media() + "/clip.mp4");
          resource.pageUrl = fixture.page();
          resource.headers.put("Referer", "https://old.example/private");
          resource.headers.put("Origin", "https://old.example");
          a.captureRequestHeaders(
              resource, Map.of("referer", fixture.page(), "user-agent", "FixtureBrowser"));
          Map<String, String> headers = a.headersFor(resource);
          assertEquals(fixture.page(), headers.get("referer"));
          assertFalse(headers.containsKey("Referer"));
          assertEquals("FixtureBrowser", headers.get("user-agent"));
          assertFalse(headers.containsKey("Origin"));
          a.captureRequestHeaders(resource, Map.of("User-Agent", "FixtureBrowser"));
          assertTrue(
              a.headersFor(resource).keySet().stream()
                  .noneMatch(k -> k.equalsIgnoreCase("Referer")));
          return null;
        });
  }

  private void awaitPlaying() throws Exception {
    await(
        "Native media did not play",
        () ->
            ui(
                a -> {
                  if (a.player != null && a.player.getPlayerError() != null)
                    fail(a.player.getPlayerError().toString());
                  return a.player != null
                      && a.player.isPlaying()
                      && a.player.getVideoSize().height == 360;
                }));
  }

  private void assertDownloaded(String id) throws Exception {
    await(
        "Export download did not complete",
        () -> {
          DownloadStore.Job job = DownloadStore.get(context, id);
          if (job != null && "failed".equals(job.state)) fail(job.error);
          return job != null && "completed".equals(job.state);
        });
    DownloadStore.Job job = DownloadStore.get(context, id);
    assertEquals("fixture.mp4", job.fileName);
    try (InputStream input =
        context.getContentResolver().openInputStream(Uri.parse(job.outputUri))) {
      assertArrayEquals(fixture.video, input.readAllBytes());
    }
  }

  private void tapExport() throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    float[] point = new float[2];
    scenario.onActivity(
        a ->
            a.current.web.evaluateJavascript(
                "(function(){const"
                    + " r=document.getElementById('export').getBoundingClientRect();return"
                    + " [r.left+r.width/2,r.top+r.height/2]})()",
                value -> {
                  try {
                    org.json.JSONArray xy = new org.json.JSONArray(value);
                    int[] at = new int[2];
                    a.current.web.getLocationOnScreen(at);
                    float density = a.getResources().getDisplayMetrics().density;
                    point[0] = at[0] + (float) xy.getDouble(0) * density;
                    point[1] = at[1] + (float) xy.getDouble(1) * density;
                  } catch (Exception e) {
                    throw new AssertionError(e);
                  } finally {
                    ready.countDown();
                  }
                }));
    assertTrue(ready.await(5, TimeUnit.SECONDS));
    tapScreen(point[0], point[1]);
  }

  private void tapScreen(float x, float y) {
    var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    long now = SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
    MotionEvent up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0);
    down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
    up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
    try {
      assertTrue(automation.injectInputEvent(down, true));
      assertTrue(automation.injectInputEvent(up, true));
    } finally {
      down.recycle();
      up.recycle();
    }
  }

  private void tapResourceAction(String text) throws Exception {
    await(
        "Media action was not available",
        () -> {
          AccessibilityNodeInfo root =
              InstrumentationRegistry.getInstrumentation()
                  .getUiAutomation()
                  .getRootInActiveWindow();
          // The browser toolbar also contains this label. Wait for the attachment action dialog.
          if (root == null || root.findAccessibilityNodeInfosByText("复制链接").isEmpty()) return false;
          for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
            if (text.contentEquals(node.getText() == null ? "" : node.getText())) {
              android.graphics.Rect bounds = new android.graphics.Rect();
              node.getBoundsInScreen(bounds);
              if (!node.isVisibleToUser() || bounds.isEmpty()) continue;
              tapScreen(bounds.centerX(), bounds.centerY());
              return true;
            }
          }
          return false;
        });
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scenario.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }

  private interface Ready {
    boolean get() throws Exception;
  }

  private void await(String message, Ready ready) throws Exception {
    long until = SystemClock.elapsedRealtime() + 20000;
    do {
      if (ready.get()) return;
      SystemClock.sleep(80);
    } while (SystemClock.elapsedRealtime() < until);
    fail(message + "; fixture requests=" + fixture.hits);
  }

  private record Hit(String path, boolean browser, String status, String referrer, String agent) {}

  private final class Fixture implements AutoCloseable {
    final List<Hit> hits = new CopyOnWriteArrayList<>();
    final ServerSocket socket = FixtureHttp.listen();
    final ExecutorService pool = Executors.newCachedThreadPool();
    final AtomicInteger rejected = new AtomicInteger();
    final byte[] video;
    volatile String privateTarget;
    volatile boolean closed;

    Fixture() throws Exception {
      try (InputStream input =
          InstrumentationRegistry.getInstrumentation()
              .getContext()
              .getAssets()
              .open("fixtures/media/sample-360.mp4")) {
        video = input.readAllBytes();
      }
      accept();
    }

    String page() {
      return media() + "/page";
    }

    String media() {
      return "http://127.0.0.1:" + socket.getLocalPort();
    }

    void accept() {
      pool.execute(
          () -> {
            while (!closed)
              try {
                Socket client = socket.accept();
                pool.execute(() -> serve(client));
              } catch (IOException e) {
                if (!closed) throw new RuntimeException(e);
              }
          });
    }

    void serve(Socket client) {
      try (Socket connection = client) {
        connection.setSoTimeout(5000);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
        String first = reader.readLine();
        if (first == null) return;
        String path = first.split(" ")[1];
        boolean isMedia =
            path.equals("/export") || path.equals("/clip.mp4") || path.startsWith("/restricted");
        Map<String, String> headers = new HashMap<>();
        for (String line; (line = reader.readLine()) != null && !line.isEmpty(); ) {
          int split = line.indexOf(':');
          if (split > 0)
            headers.put(
                line.substring(0, split).toLowerCase(Locale.ROOT),
                line.substring(split + 1).trim());
        }
        byte[] body;
        String extra = "", status = "200 OK", mime = "video/mp4";
        if (!isMedia) {
          mime = "text/html; charset=utf-8";
          extra = "Referrer-Policy: no-referrer\r\n";
          body =
              ("<!doctype html><meta name='viewport'"
                   + " content='width=device-width,initial-scale=1'><title>Export fixture</title><a"
                   + " id='export' style='display:block;padding:30px' href='"
                      + media()
                      + "/export' type='video/mp4' rel='noreferrer'>Export video</a>")
                  .getBytes(StandardCharsets.UTF_8);
        } else if (path.equals("/restricted.m3u8")) {
          mime = "application/vnd.apple.mpegurl";
          body =
              ("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\n"
                      + privateTarget
                      + "\n#EXT-X-ENDLIST\n")
                  .getBytes(StandardCharsets.UTF_8);
        } else if (path.equals("/restricted-redirect.mp4")) {
          status = "302 Found";
          extra = "Location: " + privateTarget + "\r\n";
          body = new byte[0];
        } else if (headers.containsKey("referer") && !headers.get("referer").isBlank()) {
          rejected.incrementAndGet();
          status = "403 Forbidden";
          body = new byte[0];
        } else if (path.equals("/export")) {
          status = "302 Found";
          extra = "Location: /clip.mp4\r\n";
          body = new byte[0];
        } else {
          int from = 0, to = video.length - 1;
          if (headers.containsKey("range")) {
            String[] range = headers.get("range").substring(6).split("-", -1);
            from = Integer.parseInt(range[0]);
            if (range.length > 1 && !range[1].isEmpty())
              to = Math.min(to, Integer.parseInt(range[1]));
            status = "206 Partial Content";
            extra = "Content-Range: bytes " + from + "-" + to + "/" + video.length + "\r\n";
          }
          body = Arrays.copyOfRange(video, from, to + 1);
          extra +=
              "Accept-Ranges: bytes\r\n"
                  + "Content-Disposition: attachment; filename=\"fixture.mp4\"\r\n";
        }
        if (isMedia)
          hits.add(
              new Hit(
                  path,
                  headers.containsKey("sec-fetch-mode"),
                  status,
                  headers.getOrDefault("referer", ""),
                  headers.getOrDefault("user-agent", "")));
        OutputStream out = connection.getOutputStream();
        out.write(
            ("HTTP/1.1 "
                    + status
                    + "\r\nContent-Type: "
                    + mime
                    + "\r\nContent-Length: "
                    + body.length
                    + "\r\n"
                    + extra
                    + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
      } catch (Exception ignored) {
      }
    }

    public void close() throws IOException {
      closed = true;
      socket.close();
      pool.shutdownNow();
    }
  }
}
