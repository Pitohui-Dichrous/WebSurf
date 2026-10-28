package io.websurf.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.websurf.app.download.DownloadService;
import io.websurf.app.download.DownloadStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Real task/service/UI integration using only the loopback synthetic media server. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class DownloadExperienceTest {
  private static final String BASE = "http://127.0.0.1:8765";
  private Context context;
  private ActivityScenario<MainActivity> scenario;
  private SharedPreferences preferences;
  private boolean hadAuto, oldAuto, deniedRun;
  @Rule public final TestName currentTest = new TestName();
  private final List<String> ownIds = new ArrayList<>();
  private final List<String> syntheticIds = new ArrayList<>();
  private final List<Intent> changes = new CopyOnWriteArrayList<>();
  private BroadcastReceiver receiver;

  @Before
  public void openBrowser() throws Exception {
    deniedRun =
        "true"
            .equalsIgnoreCase(
                InstrumentationRegistry.getArguments().getString("notifications_denied", "false"));
    boolean deniedCase =
        "disabledNotificationsDoNotFailDownloadOrHideInAppCompletion"
            .equals(currentTest.getMethodName());
    Assume.assumeTrue(
        "Run the denied case separately after externally revoking POST_NOTIFICATIONS",
        deniedCase == deniedRun);
    context = ApplicationProvider.getApplicationContext();
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (!deniedRun
        && Build.VERSION.SDK_INT >= 33
        && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      InstrumentationRegistry.getInstrumentation()
          .getUiAutomation()
          .grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
    }
    await(
        "Runtime notification permission does not match this test invocation",
        5000,
        () -> manager.areNotificationsEnabled() != deniedRun);
    preferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    hadAuto = preferences.contains("auto_play");
    oldAuto = preferences.getBoolean("auto_play", true);
    assertTrue(preferences.edit().putBoolean("auto_play", false).commit());
    receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context ignored, Intent intent) {
            changes.add(new Intent(intent));
          }
        };
    ContextCompat.registerReceiver(
        context,
        receiver,
        new IntentFilter(DownloadService.ACTION_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED);
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse(BASE + "/?experience=" + System.nanoTime())));
    await(
        "Browser window did not become visible",
        10000,
        () -> ui(a -> a.resumed && a.root.isShown() && a.current != null));
  }

  @After
  public void closeAndRestore() throws Exception {
    if (scenario != null) scenario.close();
    if (context != null) {
      for (String id : ownIds) {
        DownloadStore.Job job = DownloadStore.get(context, id);
        if (job != null && job.active() && !syntheticIds.contains(id))
          DownloadService.cancel(context, id);
        if (job != null && !job.outputUri.isBlank()) {
          try {
            context.getContentResolver().delete(Uri.parse(job.outputUri), null, null);
          } catch (RuntimeException ignored) {
          }
        }
        DownloadStore.remove(context, id);
        context.getSystemService(NotificationManager.class).cancel("download-" + id, 4202);
      }
      if (receiver != null) context.unregisterReceiver(receiver);
      // Permission is controlled between instrumentation runs: revoking it here can kill this
      // process.
    }
    if (preferences != null) {
      SharedPreferences.Editor edit = preferences.edit();
      if (hadAuto) edit.putBoolean("auto_play", oldAuto);
      else edit.remove("auto_play");
      assertTrue(edit.commit());
    }
  }

  @Test
  public void pausedPlaybackDoesNotThrottleDownloadAndShowsStartAndCompletion() throws Exception {
    WebResource media =
        video(
            BASE + "/public/hls/master.m3u8?experience=" + System.nanoTime(),
            "边下边播测试",
            "application/vnd.apple.mpegurl");
    ui(
        a -> {
          a.openPlayer(media, 0);
          return null;
        });
    await(
        "Native HLS player never became ready",
        20000,
        () ->
            ui(
                a ->
                    a.player != null
                        && a.player.getPlayerError() == null
                        && a.player.getPlaybackState() == Player.STATE_READY));
    ExoPlayer original = ui(a -> a.player);
    Object surface = ui(a -> a.playerView.getVideoSurfaceView());
    ui(
        a -> {
          a.player.pause();
          a.player.seekTo(2500);
          return null;
        });
    await(
        "Player did not settle at 2.5 seconds",
        7000,
        () ->
            ui(
                a ->
                    !a.player.getPlayWhenReady()
                        && Math.abs(a.player.getCurrentPosition() - 2500) < 80));
    String id = beginWithVisibleFeedback(media);
    DownloadStore.Job finished = awaitCompleted(id);
    assertTrue("Download must write actual media bytes", finished.bytes > 0);
    assertEquals(100, finished.progress);
    assertFalse(finished.outputUri.isBlank());
    assertSame(original, ui(a -> a.player));
    assertSame(surface, ui(a -> a.playerView.getVideoSurfaceView()));
    assertFalse(
        "Downloading must not resume the paused player", ui(a -> a.player.getPlayWhenReady()));
    assertEquals(2500L, (long) ui(a -> a.player.getCurrentPosition()), 150);
    assertStates(id, "queued", "downloading", "completed");
    await("Completion feedback is not visible", 7000, () -> ui(a -> feedbackContains(a, "下载完成")));
    await(
        "Completed task notification was removed with the foreground notification",
        7000,
        () -> notification(id, "下载完成") != null);
    StatusBarNotification result = notification(id, "下载完成");
    assertNotNull(result);
    assertEquals("websurf_download_results", result.getNotification().getChannelId());
    assertTrue((result.getNotification().flags & Notification.FLAG_ONGOING_EVENT) == 0);
    assertNotNull(result.getNotification().contentIntent);
    SystemClock.sleep(200);
    UiCaptureTest.shot(context, "17-download-complete");
    android.webkit.WebView sameWeb = ui(a -> a.current.web);
    long pageEpoch = ui(a -> a.current.epoch);
    double requestMark = latestRequestTime();
    ui(
        a -> {
          a.openDownloadPreview(finished);
          return null;
        });
    await(
        "Actual cached HLS preview did not become ready at 720p",
        15000,
        () ->
            ui(
                a ->
                    a.activeTarget == null
                        && a.player != null
                        && a.player != original
                        && a.player.getPlayerError() == null
                        && a.player.getPlaybackState() == Player.STATE_READY
                        && a.player.isPlaying()
                        && a.player.getVideoSize().height == 720
                        && a.player.getCurrentPosition() > 250));
    long previewPosition = ui(a -> a.player.getCurrentPosition());
    await(
        "The cached HLS preview stopped advancing",
        4000,
        () -> ui(a -> a.player.getCurrentPosition() > previewPosition + 200));
    assertSame(sameWeb, ui(a -> a.current.web));
    assertEquals(
        "Download preview reloaded the webpage", pageEpoch, (long) ui(a -> a.current.epoch));
    List<String> newPaths = hlsPathsAfter(requestMark);
    long selectedDownloadRequests =
        newPaths.stream().filter(DownloadExperienceTest::downloadedHlsPath).count();
    long otherVariantManifestRequests =
        newPaths.stream()
            .filter(path -> !downloadedHlsPath(path) && path.split("\\?", 2)[0].endsWith(".m3u8"))
            .count();
    // Preparation may inspect an unselected variant's manifest; it was never downloaded.
    System.out.println(
        "HLS_CACHE_PREVIEW bytes="
            + finished.bytes
            + " pausedCheckpointMs=2500 height=720 selectedDownloadRequests="
            + selectedDownloadRequests
            + " otherVariantManifestRequests="
            + otherVariantManifestRequests
            + " newHlsPaths="
            + newPaths);
    assertEquals(
        "Downloaded HLS master/720p resources should be cached; new paths=" + newPaths,
        0L,
        selectedDownloadRequests);
    ui(
        a -> {
          a.videoChrome.showControls();
          return null;
        });
    SystemClock.sleep(180);
    UiCaptureTest.shot(context, "19-download-preview");
  }

  @Test
  public void disabledNotificationsDoNotFailDownloadOrHideInAppCompletion() throws Exception {
    assertTrue(deniedRun);
    assertFalse(context.getSystemService(NotificationManager.class).areNotificationsEnabled());
    String id =
        beginWithVisibleFeedback(
            video(
                BASE + "/public/sample-360.mp4?experience=" + System.nanoTime(),
                "通知关闭下载测试",
                "video/mp4"));
    DownloadStore.Job finished = awaitCompleted(id);
    assertTrue(finished.bytes > 0);
    assertStates(id, "queued", "downloading", "completed");
    await(
        "In-app completion disappeared when notifications were disabled",
        7000,
        () -> ui(a -> feedbackContains(a, "下载完成")));
    assertNull("Disabled ordinary notifications must not be posted", notification(id, null));
  }

  @Test
  public void failedDownloadRetainsErrorFeedbackAndRetryNotification() throws Exception {
    String id =
        beginWithVisibleFeedback(
            video(
                BASE + "/public/experience-not-found.mp4?run=" + System.nanoTime(),
                "失败反馈测试",
                "video/mp4"));
    await(
        "Missing media was not reported as failed",
        15000,
        () -> {
          DownloadStore.Job j = DownloadStore.get(context, id);
          return j != null && "failed".equals(j.state);
        });
    assertStates(id, "queued", "downloading", "failed");
    DownloadStore.Job failed = DownloadStore.get(context, id);
    assertFalse("Failure details must remain available", failed.error.isBlank());
    await("Failure feedback is not visible", 7000, () -> ui(a -> feedbackContains(a, "下载失败")));
    await("Failure notification is missing", 7000, () -> notification(id, "下载失败") != null);
    Notification result = notification(id, "下载失败").getNotification();
    assertTrue(
        result
            .extras
            .getCharSequence(Notification.EXTRA_BIG_TEXT)
            .toString()
            .contains(failed.error));
    assertNotNull(result.actions);
    assertEquals("重试", result.actions[0].title.toString());
    assertNotNull(result.actions[0].actionIntent);
  }

  @Test
  public void downloadProgressKeepsExistingRowsScrollAndFocusedAction() throws Exception {
    List<DownloadStore.Job> rows = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      DownloadStore.Job j = new DownloadStore.Job();
      j.id = "experience-" + UUID.randomUUID();
      ownIds.add(j.id);
      syntheticIds.add(j.id);
      j.title = "持续更新任务 " + i;
      j.url = BASE + "/public/sample-360.mp4?row=" + j.id;
      j.mimeType = "video/mp4";
      j.state = "downloading";
      j.progress = 10;
      j.bytes = 1048576;
      j.total = 10485760;
      j.bytesPerSecond = 102400;
      j.connections = 8;
      j.createdAt = System.currentTimeMillis();
      persist(j);
      rows.add(j);
    }
    DownloadStore.Job selected = rows.get(8);
    ui(
        a -> {
          a.showDownloads();
          return null;
        });
    await(
        "Download list has not laid out",
        7000,
        () ->
            ui(
                a ->
                    findRow(a.activeSheet.getWindow().getDecorView(), selected.title) != null
                        && findRow(a.activeSheet.getWindow().getDecorView(), selected.title)
                            .isLaidOut()));
    DownloadTaskRow original =
        ui(a -> findRow(a.activeSheet.getWindow().getDecorView(), selected.title));
    ScrollView scroll = ancestorScroll(original);
    assertNotNull(scroll);
    Button cancel = ui(a -> (Button) findText(original, "取消"));
    assertNotNull(cancel);
    ui(
        a -> {
          scroll.scrollTo(0, a.dp(260));
          cancel.setFocusableInTouchMode(true);
          cancel.requestFocus();
          return null;
        });
    SystemClock.sleep(250);
    int before = ui(a -> scroll.getScrollY());
    assertTrue("Fixture did not create a real scrollable task list", before > 0);
    assertTrue(ui(a -> cancel.hasFocus()));
    selected.progress = 42;
    selected.bytes = 4404019;
    selected.bytesPerSecond = 204800;
    persist(selected);
    await("Task progress did not refresh", 4000, () -> ui(a -> texts(original).contains("42%")));
    assertSame(
        original, ui(a -> findRow(a.activeSheet.getWindow().getDecorView(), selected.title)));
    assertSame(cancel, ui(a -> findText(original, "取消")));
    assertTrue(
        "Progress refresh replaced or unfocused the active action", ui(a -> cancel.hasFocus()));
    assertEquals(
        "Progress refresh reset the list scroll position",
        before,
        (int) ui(a -> scroll.getScrollY()));
    UiCaptureTest.shot(context, "18-download-list");
  }

  @Test
  public void playerMenuAndSettingsDoNotOfferQualitySelection() throws Exception {
    ui(
        a -> {
          a.showSettings();
          return null;
        });
    assertFalse(ui(a -> texts(a.activeSheet.getWindow().getDecorView()).contains("画质")));
    ui(
        a -> {
          a.activeSheet.dismiss();
          a.openPlayer(video(BASE + "/public/sample-360.mp4", "播放器测试", "video/mp4"), 0);
          a.player.pause();
          a.videoChrome.showControls();
          return null;
        });
    await(
        "Player controls did not become ready",
        15000,
        () -> ui(a -> a.player != null && a.player.getPlaybackState() == Player.STATE_READY));
    assertFalse(ui(a -> texts(a.videoChrome).contains("画质")));
    ui(
        a -> {
          View more = findDescription(a.videoChrome, "更多视频操作");
          assertNotNull(more);
          assertTrue(more.performClick());
          return null;
        });
    await("Player action menu did not open", 5000, () -> accessibilityTexts().contains("选择页面视频"));
    assertFalse(accessibilityTexts().contains("画质"));
    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
  }

  @Test
  public void immediateCancelThenRetryCannotBeOverwrittenByTheOldRun() throws Exception {
    try (SlowFirstResponse server = new SlowFirstResponse()) {
      String id =
          beginWithVisibleFeedback(video(server.url(), "快速重试测试", "application/octet-stream"));
      assertTrue(
          "The first real response was never held open",
          server.firstBody.await(10, TimeUnit.SECONDS));
      await(
          "The service never entered downloading",
          5000,
          () -> "downloading".equals(DownloadStore.get(context, id).state));
      // Deliberately no wait between these actions: the original engine is still blocked on I/O.
      ui(
          a -> {
            DownloadService.cancel(a, id);
            DownloadService.retry(a, id);
            return null;
          });
      DownloadStore.Job finished = awaitCompleted(id);
      assertEquals(id, finished.id);
      assertEquals(server.payload.length, finished.bytes);
      assertTrue("Retry must issue a second real request", server.requests.get() >= 2);
      assertStates(id, "cancelled", "queued", "completed");
    }
  }

  private String beginWithVisibleFeedback(WebResource media) {
    String id =
        ui(
            a -> {
              String created =
                  DownloadService.enqueue(
                      a, media.url, media.title, a.mediaHeadersFor(media), media.mime);
              DownloadStore.Job queued = DownloadStore.get(a, created);
              assertNotNull(queued);
              a.downloadStates.put(created, queued.state);
              a.showDownloadFeedback("已加入下载", queued);
              assertTrue(feedbackContains(a, "已加入下载"));
              assertTrue(a.downloadFeedback.isShown());
              return created;
            });
    ownIds.add(id);
    return id;
  }

  private DownloadStore.Job awaitCompleted(String id) throws Exception {
    await(
        "Download did not complete",
        30000,
        () -> {
          DownloadStore.Job j = DownloadStore.get(context, id);
          if (j != null && "failed".equals(j.state)) fail(j.error);
          return j != null && "completed".equals(j.state);
        });
    return DownloadStore.get(context, id);
  }

  private void assertStates(String id, String... expected) throws Exception {
    for (String state : expected) {
      await(
          "Missing task broadcast: " + state,
          5000,
          () ->
              changes.stream()
                  .anyMatch(
                      i ->
                          id.equals(i.getStringExtra(DownloadService.EXTRA_ID))
                              && state.equals(i.getStringExtra(DownloadService.EXTRA_STATE))));
    }
    for (Intent i : changes) {
      if (!id.equals(i.getStringExtra(DownloadService.EXTRA_ID))) continue;
      assertNotNull(i.getExtras());
      assertEquals(
          "Task broadcasts must not expose URLs or request headers", 2, i.getExtras().size());
    }
  }

  private StatusBarNotification notification(String id, String title) {
    for (StatusBarNotification n :
        context.getSystemService(NotificationManager.class).getActiveNotifications()) {
      if (!("download-" + id).equals(n.getTag())) continue;
      CharSequence text = n.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
      if (title == null || title.contentEquals(text == null ? "" : text)) return n;
    }
    return null;
  }

  private JSONArray requests() throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL(BASE + "/api/requests").openConnection();
    connection.setConnectTimeout(3000);
    connection.setReadTimeout(5000);
    try (java.io.InputStream input = connection.getInputStream()) {
      return new JSONArray(
          new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    } finally {
      connection.disconnect();
    }
  }

  private double latestRequestTime() throws Exception {
    JSONArray records = requests();
    double latest = 0;
    for (int i = 0; i < records.length(); i++)
      latest = Math.max(latest, records.getJSONObject(i).optDouble("time"));
    return latest;
  }

  private static boolean downloadedHlsPath(String path) {
    String clean = path.split("\\?", 2)[0];
    return clean.equals("/public/hls/master.m3u8")
        || clean.startsWith("/public/hls/renditions/720/");
  }

  private List<String> hlsPathsAfter(double mark) throws Exception {
    JSONArray records = requests();
    List<String> paths = new ArrayList<>();
    for (int i = 0; i < records.length(); i++) {
      org.json.JSONObject row = records.getJSONObject(i);
      if (row.optDouble("time") > mark && row.optString("path").startsWith("/public/hls/"))
        paths.add(row.optString("path"));
    }
    return paths;
  }

  private static boolean feedbackContains(MainActivity a, String text) {
    return a.downloadFeedback != null
        && a.downloadFeedback.getParent() == a.root
        && a.downloadFeedback.getVisibility() == View.VISIBLE
        && texts(a.downloadFeedback).contains(text);
  }

  private static WebResource video(String url, String title, String mime) {
    WebResource r = new WebResource(url);
    r.title = title;
    r.mime = mime;
    r.classify();
    return r;
  }

  private void persist(DownloadStore.Job job) throws Exception {
    Method put =
        DownloadStore.class.getDeclaredMethod("put", Context.class, DownloadStore.Job.class);
    put.setAccessible(true);
    put.invoke(null, context, job);
  }

  private static DownloadTaskRow findRow(View view, String title) {
    if (view instanceof DownloadTaskRow row && findText(row, title) != null) return row;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        DownloadTaskRow found = findRow(group.getChildAt(i), title);
        if (found != null) return found;
      }
    return null;
  }

  private static View findText(View view, String text) {
    if (view instanceof TextView value && text.contentEquals(value.getText())) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findText(group.getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private static View findDescription(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findDescription(group.getChildAt(i), description);
        if (found != null) return found;
      }
    return null;
  }

  private static ScrollView ancestorScroll(View view) {
    for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
      if (parent instanceof ScrollView scroll) return scroll;
    return null;
  }

  private static String texts(View view) {
    StringBuilder out = new StringBuilder();
    if (view instanceof TextView text) out.append(text.getText()).append(' ');
    if (view.getContentDescription() != null) out.append(view.getContentDescription()).append(' ');
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) out.append(texts(group.getChildAt(i)));
    return out.toString();
  }

  private String accessibilityTexts() {
    AccessibilityNodeInfo root =
        InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
    return accessibilityTexts(root);
  }

  private static String accessibilityTexts(AccessibilityNodeInfo node) {
    if (node == null) return "";
    StringBuilder out = new StringBuilder();
    if (node.getText() != null) out.append(node.getText()).append(' ');
    for (int i = 0; i < node.getChildCount(); i++) out.append(accessibilityTexts(node.getChild(i)));
    return out.toString();
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scenario.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }

  /** A tiny in-process server; the first transfer cannot finish before cancellation. */
  private static final class SlowFirstResponse implements AutoCloseable {
    final byte[] payload = new byte[256 * 1024];
    final AtomicInteger requests = new AtomicInteger();
    final CountDownLatch firstBody = new CountDownLatch(1);
    final CountDownLatch releaseFirst = new CountDownLatch(1);
    final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    final ServerSocket server;
    final ExecutorService workers =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "websurf-experience-http");
              t.setDaemon(true);
              return t;
            });
    volatile boolean closed;

    SlowFirstResponse() throws Exception {
      for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
      server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
      workers.execute(
          () -> {
            while (!closed) {
              try {
                Socket socket = server.accept();
                clients.add(socket);
                workers.execute(() -> respond(socket));
              } catch (Exception ignored) {
                if (!closed) firstBody.countDown();
              }
            }
          });
    }

    String url() {
      return "http://127.0.0.1:" + server.getLocalPort() + "/retry.bin";
    }

    void respond(Socket socket) {
      try (socket) {
        socket.setSoTimeout(15000);
        BufferedReader in =
            new BufferedReader(
                new InputStreamReader(
                    socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
        String request = in.readLine();
        if (request == null) return;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {}
        int number = requests.incrementAndGet();
        OutputStream out = socket.getOutputStream();
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Length: "
                    + payload.length
                    + "\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Connection: close\r\n"
                    + "Cache-Control: no-store\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (number == 1) {
          out.write(payload, 0, 1024);
          out.flush();
          firstBody.countDown();
          releaseFirst.await(30, TimeUnit.SECONDS);
          if (!closed) out.write(payload, 1024, payload.length - 1024);
        } else out.write(payload);
        out.flush();
      } catch (Exception ignored) {
        // A disconnect is the expected result for the deliberately cancelled first request.
      } finally {
        clients.remove(socket);
      }
    }

    @Override
    public void close() throws Exception {
      closed = true;
      releaseFirst.countDown();
      server.close();
      for (Socket socket : clients)
        try {
          socket.close();
        } catch (Exception ignored) {
        }
      workers.shutdownNow();
    }
  }

  private interface Ready {
    boolean value() throws Exception;
  }

  private void await(String message, long timeout, Ready ready) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeout;
    do {
      if (ready.value()) return;
      SystemClock.sleep(60);
    } while (SystemClock.elapsedRealtime() < end);
    fail(message);
  }
}
