package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;

/** Local synthetic media only; BrowserFlowTest independently asserts the behavior. */
public class UiCaptureTest {
  private ActivityScenario<MainActivity> scenario;

  @Test
  public void captureBrowserAndPanels() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    SharedPreferences prefs = context.getSharedPreferences("browser", 0);
    boolean hadAuto = prefs.contains("auto_play"), oldAuto = prefs.getBoolean("auto_play", true);
    boolean oldDark = Ui.isDark(context);
    prefs.edit().putBoolean("auto_play", true).commit();
    Ui.setDark(context, false);
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse("http://127.0.0.1:8765/?capture")));
    try {
      await(
          "Fixture did not load",
          15000,
          () ->
              Boolean.TRUE.equals(
                  js(
                      "Boolean(window.__fixture&&window.__websurf&&document.getElementById('video').readyState>=1)")));
      int originalOrientation = ui(a -> a.getResources().getConfiguration().orientation);
      shot(context, "01-browser");
      tapElement("play");
      await(
          "Native player did not become ready",
          20000,
          () ->
              ui(
                  a ->
                      a.player != null
                          && a.player.getPlayerError() == null
                          && a.player.isPlaying()
                          && a.player.getVideoSize().height == 360));
      js("document.getElementById('video').scrollIntoView({block:'center',behavior:'instant'})");
      ui(
          a -> {
            a.player.pause();
            a.videoChrome.showControls();
            return null;
          });
      VideoControlsTest.awaitPresentation(scenario, VideoChrome.Mode.INLINE, originalOrientation);
      shot(context, "02-player-inline");
      ui(
          a -> {
            a.setPlayerMode(VideoChrome.Mode.FULLSCREEN);
            return null;
          });
      VideoControlsTest.awaitPresentation(
          scenario, VideoChrome.Mode.FULLSCREEN, originalOrientation);
      // WindowManager rotation may still animate the composed surface after view layout settles.
      SystemClock.sleep(700);
      shot(context, "03-player-fullscreen");
      ui(
          a -> {
            a.setPlayerMode(VideoChrome.Mode.FLOATING);
            return null;
          });
      VideoControlsTest.awaitPresentation(scenario, VideoChrome.Mode.FLOATING, originalOrientation);
      SystemClock.sleep(700);
      shot(context, "04-player-floating");
      ui(
          a -> {
            a.setPlayerMode(VideoChrome.Mode.INLINE);
            return null;
          });
      VideoControlsTest.awaitPresentation(scenario, VideoChrome.Mode.INLINE, originalOrientation);
      ui(
          a -> {
            a.closePlayer(true, true);
            a.showResources("视频");
            return null;
          });
      awaitSheet(originalOrientation);
      await(
          "Resource thumbnails did not load",
          10000,
          () -> ui(a -> hasPreview(a.activeSheet.getWindow().getDecorView())));
      SystemClock.sleep(250);
      shot(context, "05-resources");
      ui(
          a -> {
            a.showSettings();
            return null;
          });
      awaitSheet(originalOrientation);
      shot(context, "06-settings");
      ui(
          a -> {
            a.activeSheet.dismiss();
            Ui.setDark(a, true);
            Ui.configure(a);
            a.applyChromeTheme();
            a.showResources("视频");
            return null;
          });
      awaitSheet(originalOrientation);
      await(
          "Dark resource thumbnails did not load",
          10000,
          () -> ui(a -> hasPreview(a.activeSheet.getWindow().getDecorView())));
      SystemClock.sleep(250);
      shot(context, "07-dark-resources");
      String downloadId =
          ui(
              a ->
                  io.websurf.app.download.DownloadService.enqueue(
                      a,
                      "http://127.0.0.1:8765/public/hls/master.m3u8",
                      "WebSurf 720p",
                      java.util.Collections.emptyMap(),
                      "application/vnd.apple.mpegurl"));
      await(
          "HLS download did not complete",
          25000,
          () -> {
            io.websurf.app.download.DownloadStore.Job job =
                io.websurf.app.download.DownloadStore.get(context, downloadId);
            return job != null && !job.active();
          });
      io.websurf.app.download.DownloadStore.Job downloaded =
          io.websurf.app.download.DownloadStore.get(context, downloadId);
      assertNotNull(downloaded);
      assertEquals(downloaded.error, "completed", downloaded.state);
      ui(
          a -> {
            a.activeSheet.dismiss();
            Ui.setDark(a, false);
            Ui.configure(a);
            a.applyChromeTheme();
            a.showDownloads();
            return null;
          });
      awaitSheet(originalOrientation);
      shot(context, "08-downloads");
      ui(
          a -> {
            a.openDownloaded(downloaded);
            return null;
          });
      await(
          "Downloaded MP4 did not play",
          15000,
          () ->
              ui(
                  a ->
                      a.player != null
                          && a.player.getPlayerError() == null
                          && a.player.isPlaying()));
      ui(
          a -> {
            a.player.pause();
            a.videoChrome.showControls();
            return null;
          });
      VideoControlsTest.awaitPresentation(scenario, VideoChrome.Mode.FLOATING, originalOrientation);
      shot(context, "09-downloaded-player");
    } finally {
      if (scenario != null) scenario.close();
      Ui.setDark(context, oldDark);
      SharedPreferences.Editor edit = prefs.edit();
      if (hadAuto) edit.putBoolean("auto_play", oldAuto);
      else edit.remove("auto_play");
      edit.commit();
    }
  }

  private void awaitSheet(int orientation) throws Exception {
    await(
        "Sheet opened before orientation or layout settled",
        8000,
        () ->
            ui(
                a -> {
                  if (!VideoControlsTest.windowReady(a, orientation, false)
                      || a.activeSheet == null
                      || !a.activeSheet.isShowing()
                      || a.activeSheet.getWindow() == null) return false;
                  View decor = a.activeSheet.getWindow().getDecorView();
                  ViewGroup content = decor.findViewById(android.R.id.content);
                  if (content == null
                      || content.getChildCount() == 0
                      || !decor.isLaidOut()
                      || decor.isLayoutRequested()
                      || decor.getWidth() != a.root.getWidth()) return false;
                  View box = content.getChildAt(0);
                  return box.isLaidOut()
                      && !box.isLayoutRequested()
                      && box.getHeight() > 0
                      && box.getAlpha() >= .999f
                      && Math.abs(box.getTranslationY()) < .5f;
                }));
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scenario.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }

  private Object js(String source) throws Exception {
    AtomicReference<String> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    scenario.onActivity(
        a ->
            a.current.web.evaluateJavascript(
                source,
                value -> {
                  result.set(value);
                  latch.countDown();
                }));
    assertTrue("JavaScript callback timed out", latch.await(5, TimeUnit.SECONDS));
    String value = result.get();
    return value == null ? JSONObject.NULL : new JSONTokener(value).nextValue();
  }

  private void tapElement(String id) throws Exception {
    String element = "document.getElementById(" + JSONObject.quote(id) + ")";
    js(element + ".scrollIntoView({block:'center',behavior:'instant'})");
    SystemClock.sleep(180);
    JSONObject rect =
        (JSONObject)
            js(
                "(function(){var r="
                    + element
                    + ".getBoundingClientRect();return"
                    + " {x:r.left+r.width/2,y:r.top+r.height/2,vw:window.innerWidth};})()");
    float[] web =
        ui(
            a -> {
              int[] at = new int[2];
              a.current.web.getLocationOnScreen(at);
              return new float[] {at[0], at[1], a.current.web.getWidth()};
            });
    float scale = web[2] / (float) rect.getDouble("vw"),
        x = web[0] + (float) rect.getDouble("x") * scale,
        y = web[1] + (float) rect.getDouble("y") * scale;
    long time = SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
    MotionEvent up = MotionEvent.obtain(time, time + 70, MotionEvent.ACTION_UP, x, y, 0);
    try {
      InstrumentationRegistry.getInstrumentation().sendPointerSync(down);
      SystemClock.sleep(70);
      InstrumentationRegistry.getInstrumentation().sendPointerSync(up);
    } finally {
      down.recycle();
      up.recycle();
    }
  }

  private interface Ready {
    boolean value() throws Exception;
  }

  private void await(String message, long timeout, Ready condition) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeout;
    do {
      if (condition.value()) return;
      SystemClock.sleep(100);
    } while (SystemClock.elapsedRealtime() < end);
    fail(message);
  }

  static void shot(Context context, String name) throws Exception {
    Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull("Screenshot failed", bitmap);
    File folder = new File(context.getExternalFilesDir(null), "qa");
    assertTrue(folder.isDirectory() || folder.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
  }

  private static boolean hasPreview(View view) {
    if (view instanceof android.widget.ImageView image
        && image.isShown()
        && image.getDrawable() != null) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasPreview(group.getChildAt(i))) return true;
    return false;
  }
}
