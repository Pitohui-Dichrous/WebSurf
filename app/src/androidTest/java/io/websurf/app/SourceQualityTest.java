package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.WebView;
import android.widget.ListView;
import androidx.media3.common.Player;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** MainActivity integration with two explicitly labelled sources of the same DOM video. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class SourceQualityTest {
  private ActivityScenario<MainActivity> scenario;
  private SharedPreferences prefs;
  private boolean hadAuto, oldAuto, hadPreferred, oldDark;
  private int oldPreferred;

  @Before
  public void openDeclaredSources() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    oldDark = Ui.isDark(context);
    Ui.setDark(context, false);
    prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    hadAuto = prefs.contains("auto_play");
    oldAuto = prefs.getBoolean("auto_play", true);
    hadPreferred = prefs.contains("preferred_quality");
    oldPreferred = prefs.getInt("preferred_quality", 0);
    assertTrue(prefs.edit().putBoolean("auto_play", false).putInt("preferred_quality", 0).commit());
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse("http://127.0.0.1:8765/quality.html?run=" + System.nanoTime())));
    awaitPageAt360();
  }

  @After
  public void closeAndRestorePreferences() {
    if (scenario != null) scenario.close();
    Ui.setDark(ApplicationProvider.getApplicationContext(), oldDark);
    if (prefs != null) {
      SharedPreferences.Editor edit = prefs.edit();
      if (hadAuto) edit.putBoolean("auto_play", oldAuto);
      else edit.remove("auto_play");
      if (hadPreferred) edit.putInt("preferred_quality", oldPreferred);
      else edit.remove("preferred_quality");
      assertTrue(edit.commit());
    }
  }

  @Test
  public void nativeQualityDialogSwitchesDeclaredSourceWithoutReloadAndKeepsPauseAndPosition()
      throws Exception {
    WebView sameWeb = ui(a -> a.current.web);
    String instance = (String) js("window.__qualityFixture.instance");
    int loads = ((Number) js("window.__qualityFixture.loads")).intValue();
    long epoch = ui(a -> a.current.epoch);
    js("document.getElementById('note').value='keep quality form'; true");
    openDefaultAndPauseAt2500();
    String key = ui(a -> a.activeTarget.key());
    long version = ui(a -> a.activeTarget.sourceVersion);
    choose720FromDialog();
    awaitReadyHeight(720);
    assertSame(sameWeb, ui(a -> a.current.web));
    assertEquals(epoch, (long) ui(a -> a.current.epoch));
    assertEquals(instance, js("window.__qualityFixture.instance"));
    assertEquals(loads, ((Number) js("window.__qualityFixture.loads")).intValue());
    assertEquals("keep quality form", js("document.getElementById('note').value"));
    assertEquals(key, ui(a -> a.activeTarget.key()));
    assertTrue(ui(a -> a.activeTarget.sourceVersion > version));
    assertFalse(ui(a -> a.player.getPlayWhenReady()));
    assertEquals(2500L, (long) ui(a -> a.player.getCurrentPosition()), 180);
    assertEquals(720, prefs.getInt("preferred_quality", 0));
    assertTrue((Boolean) js("document.getElementById('video').paused"));
    assertEquals(
        2.5, ((Number) js("document.getElementById('video').currentTime")).doubleValue(), .18);
    SystemClock.sleep(250);
    UiCaptureTest.shot(ApplicationProvider.getApplicationContext(), "15-quality-720p");
  }

  @Test
  public void manualTakeoverAfterRefreshRestoresPreferredQualityFromDefault360() throws Exception {
    ui(
        a -> {
          Ui.setDark(a, true);
          a.applyChromeTheme();
          return null;
        });
    openDefaultAndPauseAt2500();
    choose720FromDialog();
    awaitReadyHeight(720);
    assertEquals(720, prefs.getInt("preferred_quality", 0));
    WebView sameWeb = ui(a -> a.current.web);
    String oldInstance = (String) js("window.__qualityFixture.instance");
    int oldLoads = ((Number) js("window.__qualityFixture.loads")).intValue();
    ui(
        a -> {
          a.current.web.reload();
          return null;
        });
    await(
        "Refresh did not create a new document",
        10000,
        () -> !oldInstance.equals(js("window.__qualityFixture&&window.__qualityFixture.instance")));
    awaitPageAt360();
    assertNull("Refreshing a page must not auto-play its default video", ui(a -> a.player));
    assertEquals(oldLoads + 1, ((Number) js("window.__qualityFixture.loads")).intValue());
    assertTrue(
        ((String) js("document.getElementById('video').currentSrc")).contains("sample-360.mp4"));
    String refreshedInstance = (String) js("window.__qualityFixture.instance");
    int refreshedLoads = ((Number) js("window.__qualityFixture.loads")).intValue();
    ui(
        a -> {
          a.openTarget(a.current, defaultTarget(a), true);
          return null;
        });
    awaitReadyHeight(720);
    assertSame(sameWeb, ui(a -> a.current.web));
    assertEquals(refreshedInstance, js("window.__qualityFixture.instance"));
    assertEquals(refreshedLoads, ((Number) js("window.__qualityFixture.loads")).intValue());
    assertTrue(
        ui(a -> a.player.getPlayWhenReady() && a.activeTarget.url.contains("sample-720.mp4")));
  }

  private void openDefaultAndPauseAt2500() throws Exception {
    ui(
        a -> {
          a.openTarget(a.current, defaultTarget(a), true);
          return null;
        });
    awaitReadyHeight(360);
    ui(
        a -> {
          a.player.pause();
          a.player.seekTo(2500);
          return null;
        });
    await(
        "Native playback did not settle at the paused position",
        5000,
        () ->
            ui(
                a ->
                    !a.player.getPlayWhenReady()
                        && Math.abs(a.player.getCurrentPosition() - 2500) < 50));
  }

  private void choose720FromDialog() throws Exception {
    ui(
        a -> {
          a.showQualityOptions();
          return null;
        });
    await(
        "Quality list did not open",
        5000,
        () ->
            ui(
                a ->
                    a.qualityDialog != null
                        && a.qualityDialog.isShowing()
                        && a.qualityDialog.getListView() != null
                        && a.qualityDialog.getListView().getChildCount() > 0));
    SystemClock.sleep(250);
    Context context = ApplicationProvider.getApplicationContext();
    UiCaptureTest.shot(context, Ui.isDark(context) ? "16-quality-dark" : "14-quality-sources");
    ui(
        a -> {
          ListView list = a.qualityDialog.getListView();
          int match = -1;
          for (int i = 0; i < list.getAdapter().getCount(); i++) {
            String label = String.valueOf(list.getAdapter().getItem(i));
            if (label.startsWith("720p") && label.contains("网页")) match = i;
          }
          assertTrue("Explicit 720p source was not offered by MainActivity", match >= 0);
          assertTrue(
              list.performItemClick(
                  list.getChildAt(match - list.getFirstVisiblePosition()),
                  match,
                  list.getAdapter().getItemId(match)));
          return null;
        });
  }

  private void awaitPageAt360() throws Exception {
    await(
        "Declared quality fixture is unavailable; check the /quality.html route and 8765 reverse",
        15000,
        () ->
            Boolean.TRUE.equals(
                    js(
                        "Boolean(window.__qualityFixture&&window.__websurf&&document.getElementById('video').readyState>=1&&document.getElementById('video').currentSrc.includes('sample-360.mp4'))"))
                && ui(
                    a ->
                        a.current != null
                            && a.current.videos.values().stream()
                                .anyMatch(
                                    t ->
                                        t.url.contains("sample-360.mp4")
                                            && t.qualitySources.size() == 2
                                            && t.height == 360)));
  }

  private void awaitReadyHeight(int height) throws Exception {
    await(
        "Native " + height + "p did not become ready",
        15000,
        () ->
            ui(
                a ->
                    a.player != null
                        && a.player.getPlayerError() == null
                        && a.player.getPlaybackState() == Player.STATE_READY
                        && a.player.getVideoSize().height == height));
  }

  private static VideoTarget defaultTarget(MainActivity a) {
    return a.current.videos.values().stream()
        .filter(t -> t.url.contains("sample-360.mp4") && t.qualitySources.size() == 2)
        .findFirst()
        .orElseThrow();
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scenario.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }

  private Object js(String expression) throws Exception {
    AtomicReference<String> result = new AtomicReference<>();
    CountDownLatch ready = new CountDownLatch(1);
    ui(
        a -> {
          a.current.web.evaluateJavascript(
              "(function(){"
                  + (expression.contains(";") ? expression : "return (" + expression + ");")
                  + "})()",
              value -> {
                result.set(value);
                ready.countDown();
              });
          return null;
        });
    assertTrue("JavaScript did not answer", ready.await(5, TimeUnit.SECONDS));
    if (result.get() == null || result.get().equals("null")) return JSONObject.NULL;
    return new JSONTokener(result.get()).nextValue();
  }

  private interface Ready {
    boolean value() throws Exception;
  }

  private void await(String message, long timeout, Ready condition) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    do {
      if (condition.value()) return;
      SystemClock.sleep(60);
    } while (SystemClock.elapsedRealtime() < deadline);
    fail(
        message
            + " [native="
            + ui(
                a ->
                    "epoch="
                        + a.current.epoch
                        + ",docs="
                        + a.current.documents.size()
                        + ",videos="
                        + a.current.videos.values().stream()
                            .map(t -> t.url + ":" + t.height + ":q" + t.qualitySources.size())
                            .toList())
            + ",page="
            + js(
                "JSON.stringify({instance:window.__qualityFixture,ready:document.getElementById('video').readyState,src:document.getElementById('video').currentSrc})")
            + "]");
  }
}
