package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.WebView;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** A real native HTTP failure with a working HTML decoder, not a fabricated observer message. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerRecoveryTest extends RecoveryFixture {
  @Test
  public void nativeFailureRestoresWebVideoWithoutReloadOrFalseMediaError() throws Exception {
    WebView originalWeb = ui(a -> a.current.web);
    String instance = String.valueOf(js("window.__recovery.instance"));
    int loads = ((Number) js("window.__recovery.loadCount")).intValue();
    int mediaLoads = ((Number) js("window.__recovery.loadCalls")).intValue();
    int loadStarts = ((Number) js("window.__recovery.loadStarts")).intValue();
    js(
        "document.getElementById('note').value='keep recovery"
            + " input';window.__originalRecoveryVideo=document.getElementById('video');true");
    failNativeAndAwaitWebPlayback();
    assertSame(originalWeb, ui(a -> a.current.web));
    assertEquals(instance, js("window.__recovery.instance"));
    assertEquals(loads, ((Number) js("window.__recovery.loadCount")).intValue());
    assertEquals(mediaLoads, ((Number) js("window.__recovery.loadCalls")).intValue());
    assertEquals(loadStarts, ((Number) js("window.__recovery.loadStarts")).intValue());
    assertEquals("keep recovery input", js("document.getElementById('note').value"));
    assertEquals(
        Boolean.TRUE, js("window.__originalRecoveryVideo===document.getElementById('video')"));
    assertEquals(0, ((Number) js("window.__recovery.errors")).intValue());
    assertNull(ui(a -> a.videoChrome));
    assertNull(ui(a -> a.anchorClip));
    assertEquals(Boolean.TRUE, js("Boolean(document.querySelector('[data-websurf-control]'))"));
    int rejected = rejectedNativeRequests();
    double position = ((Number) js("document.getElementById('video').currentTime")).doubleValue();
    long until = SystemClock.elapsedRealtime() + 1400;
    do {
      assertNull("The same failed source must not enter a takeover loop", ui(a -> a.player));
      assertEquals(0, ((Number) js("window.__recovery.errors")).intValue());
      SystemClock.sleep(100);
    } while (SystemClock.elapsedRealtime() < until);
    assertEquals(
        "The native source was retried without a new source/user action",
        rejected,
        rejectedNativeRequests());
    assertTrue(
        "The restored HTML decoder stopped advancing",
        ((Number) js("document.getElementById('video').currentTime")).doubleValue()
            > position + .3);
  }

  @Test
  public void webPrerollEndedCanReplaceFailedSourceAndRetakeMainVideo() throws Exception {
    String instance = String.valueOf(js("window.__recovery.instance"));
    failNativeAndAwaitWebPlayback();
    String selected = ui(a -> a.current.selectedVideo);
    long failedVersion = ui(a -> a.current.videos.get(selected).sourceVersion);
    int clicks = ((Number) js("window.__recovery.playRequests")).intValue();
    // Exercise the site's own ended handler, including its source/load/play sequence.
    js(
        "window.__recovery.advanceAfterAd=true;document.getElementById('video').currentTime=document.getElementById('video').duration-.45;true");
    await(
        "Web preroll did not reach its real ended handler",
        7000,
        () -> ((Number) js("window.__recovery.ended")).intValue() == 1);
    awaitNativeHeight(720);
    assertEquals(selected, ui(a -> a.activeTarget.key()));
    assertTrue(ui(a -> a.activeTarget.sourceVersion > failedVersion));
    assertTrue(ui(a -> a.activeTarget.url.contains("/public/sample-720.mp4")));
    assertEquals(instance, js("window.__recovery.instance"));
    assertEquals(0, ((Number) js("window.__recovery.errors")).intValue());
    assertEquals(clicks + 1, ((Number) js("window.__recovery.playRequests")).intValue());
    assertEquals(VideoChrome.Mode.INLINE, ui(a -> a.playerMode));
  }

  @Test
  public void lateFailureFromReleasedPlayerCannotCloseItsSuccessor() throws Exception {
    js("window.__recovery.usePublic();true");
    await(
        "Public source was not observed",
        6000,
        () ->
            ui(
                a ->
                    a.current.videos.values().stream()
                        .anyMatch(t -> t.url.contains("/public/sample-360.mp4"))));
    tapPlay();
    awaitNativeHeight(360);
    ExoPlayer previous = ui(a -> a.player);
    js("window.__recovery.advance();true");
    awaitNativeHeight(720);
    ExoPlayer successor = ui(a -> a.player);
    assertNotSame(previous, successor);
    String key = ui(a -> a.activeTarget.key());
    long version = ui(a -> a.activeTarget.sourceVersion);
    ui(
        a -> {
          a.recoverNativeFailure(previous);
          return null;
        });
    SystemClock.sleep(350);
    assertSame(successor, ui(a -> a.player));
    assertEquals(key, ui(a -> a.activeTarget.key()));
    assertEquals(version, (long) ui(a -> a.activeTarget.sourceVersion));
    assertTrue(ui(a -> a.player.isPlaying()));
    assertEquals(0, ((Number) js("window.__recovery.errors")).intValue());
  }
}

/** Shared harness for recovery and resource-panel checks; only fixture media is requested. */
abstract class RecoveryFixture {
  static final String BASE = "http://127.0.0.1:8765";
  ActivityScenario<MainActivity> scenario;
  Context context;
  String run;
  private SharedPreferences preferences;
  private boolean hadAuto, oldAuto;

  @Before
  public void openRecoveryFixture() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    preferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    hadAuto = preferences.contains("auto_play");
    oldAuto = preferences.getBoolean("auto_play", true);
    assertTrue(preferences.edit().putBoolean("auto_play", true).commit());
    run = Long.toString(System.nanoTime());
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse(BASE + "/recovery.html?run=" + run)));
    await(
        "Recovery fixture's HTML video did not load; check server/reverse and Sec-Fetch-Dest"
            + " support",
        15000,
        () ->
            Boolean.TRUE.equals(
                js(
                    "Boolean(window.__recovery&&window.__websurf&&document.getElementById('video').readyState>=1)")));
    await("Video candidate was not observed", 5000, () -> ui(a -> !a.current.videos.isEmpty()));
  }

  @After
  public void closeRecoveryFixture() {
    if (scenario != null) scenario.close();
    if (preferences != null) {
      SharedPreferences.Editor edit = preferences.edit();
      if (hadAuto) edit.putBoolean("auto_play", oldAuto);
      else edit.remove("auto_play");
      edit.commit();
    }
  }

  void failNativeAndAwaitWebPlayback() throws Exception {
    tapPlay();
    await(
        "Native request was not rejected by the real HTTP fixture",
        10000,
        () -> rejectedNativeRequests() > 0);
    await(
        "Failed native takeover did not return its picture and playback to the web video",
        10000,
        () ->
            ui(a -> a.player == null && a.videoChrome == null)
                && Boolean.TRUE.equals(
                    js(
                        "return (function(){var v=document.getElementById('video');return"
                            + " !v.paused&&v.currentTime>.05&&v.readyState>=2&&getComputedStyle(v).opacity!=='0'&&v.error===null})()")));
  }

  int rejectedNativeRequests() throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL(BASE + "/api/requests").openConnection();
    connection.setConnectTimeout(2500);
    connection.setReadTimeout(2500);
    try {
      JSONArray entries =
          new JSONArray(
              new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      int found = 0;
      for (int i = 0; i < entries.length(); i++) {
        JSONObject entry = entries.getJSONObject(i);
        if (entry.optString("path").startsWith("/browser-only/")
            && entry.optString("path").contains("recovery=" + run)
            && entry.optInt("status") == 403
            && !"video".equals(entry.optString("fetchDest"))) found++;
      }
      return found;
    } finally {
      connection.disconnect();
    }
  }

  void awaitNativeHeight(int height) throws Exception {
    await(
        "Expected native " + height + "p playback",
        15000,
        () ->
            ui(
                a ->
                    a.player != null
                        && a.player.getPlayerError() == null
                        && a.player.getPlaybackState() == Player.STATE_READY
                        && a.player.getVideoSize().height == height
                        && a.player.isPlaying()));
  }

  void tapPlay() throws Exception {
    js("document.getElementById('play').scrollIntoView({block:'center',behavior:'instant'});true");
    SystemClock.sleep(150);
    JSONObject rect =
        (JSONObject)
            js(
                "return (function(){var"
                    + " r=document.getElementById('play').getBoundingClientRect();return"
                    + " {x:r.left+r.width/2,y:r.top+r.height/2,vw:innerWidth}})()");
    float[] at =
        ui(
            a -> {
              int[] point = new int[2];
              a.current.web.getLocationOnScreen(point);
              return new float[] {point[0], point[1], a.current.web.getWidth()};
            });
    float scale = at[2] / (float) rect.getDouble("vw");
    float x = at[0] + (float) rect.getDouble("x") * scale,
        y = at[1] + (float) rect.getDouble("y") * scale;
    long down = SystemClock.uptimeMillis();
    MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
    MotionEvent release = MotionEvent.obtain(down, down + 65, MotionEvent.ACTION_UP, x, y, 0);
    try {
      InstrumentationRegistry.getInstrumentation().sendPointerSync(press);
      SystemClock.sleep(65);
      InstrumentationRegistry.getInstrumentation().sendPointerSync(release);
    } finally {
      press.recycle();
      release.recycle();
    }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> value = new AtomicReference<>();
    scenario.onActivity(a -> value.set(action.apply(a)));
    return value.get();
  }

  Object js(String script) throws Exception {
    AtomicReference<String> value = new AtomicReference<>();
    CountDownLatch result = new CountDownLatch(1);
    scenario.onActivity(
        a ->
            a.current.web.evaluateJavascript(
                "(function(){"
                    + (script.contains(";") ? script : "return (" + script + ");")
                    + "})()",
                response -> {
                  value.set(response);
                  result.countDown();
                }));
    assertTrue("Timed out waiting for JavaScript", result.await(5, TimeUnit.SECONDS));
    return value.get() == null || "null".equals(value.get())
        ? JSONObject.NULL
        : new JSONTokener(value.get()).nextValue();
  }

  interface Condition {
    boolean ready() throws Exception;
  }

  void await(String message, long milliseconds, Condition condition) throws Exception {
    long until = SystemClock.elapsedRealtime() + milliseconds;
    do {
      if (condition.ready()) return;
      SystemClock.sleep(80);
    } while (SystemClock.elapsedRealtime() < until);
    fail(
        message
            + ui(
                a ->
                    " [player="
                        + a.player
                        + ", selected="
                        + a.current.selectedVideo
                        + (a.player == null
                            ? ""
                            : ", state="
                                + a.player.getPlaybackState()
                                + ", intent="
                                + a.player.getPlayWhenReady()
                                + ", position="
                                + a.player.getCurrentPosition()
                                + ", height="
                                + a.player.getVideoSize().height
                                + ", error="
                                + a.player.getPlayerError())
                        + ", target="
                        + (a.activeTarget == null
                            ? "none"
                            : a.activeTarget.url
                                + ":"
                                + a.activeTarget.sourceVersion
                                + ":"
                                + a.activeTarget.paused)
                        + "]")
            + " page="
            + js(
                "JSON.stringify({events:window.__recovery.events,paused:document.getElementById('video').paused,time:document.getElementById('video').currentTime,src:document.getElementById('video').currentSrc})"));
  }
}
