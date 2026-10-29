package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.TextureView;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The native first-frame seed must not replace a useful three-second preview with a black intro.
 */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class NativePreviewTimingTest {
  private ActivityScenario<MainActivity> scenario;

  @Test
  public void pausedBlackOpeningKeepsDecodedPreviewUntilNaturalPlaybackReachesThreeSeconds()
      throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    SharedPreferences prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    boolean hadAuto = prefs.contains("auto_play"), oldAuto = prefs.getBoolean("auto_play", true);
    assertTrue(prefs.edit().putBoolean("auto_play", false).commit());
    try {
      scenario =
          ActivityScenario.launch(
              new Intent(context, MainActivity.class)
                  .setData(Uri.parse("http://127.0.0.1:8765/layout.html?preview-timing")));
      await(
          "Fixture page did not finish loading",
          10000,
          () -> ui(a -> a.current.web.getProgress() == 100));
      WebResource resource =
          new WebResource(
              "http://127.0.0.1:8765/public/preview-black-intro.mp4?native-preview-timing="
                  + System.nanoTime());
      resource.title = "三秒预览测试";
      ExoPlayer original =
          ui(
              a -> {
                a.startPlayer(resource, 0, null, VideoChrome.Mode.FLOATING, false);
                return a.player;
              });
      await(
          "Paused native first frame did not render",
          15000,
          () ->
              ui(
                  a ->
                      a.player == original
                          && a.playerRenderedFrame
                          && a.player.getPlaybackState() == Player.STATE_READY
                          && a.player.getDuration() == 5000
                          && !a.player.getPlayWhenReady()));
      // Both delayed first-frame and video-size callbacks have time to try seeding the intro.
      SystemClock.sleep(450);
      ui(
          a -> {
            assertTrue(a.playerView.getVideoSurfaceView() instanceof TextureView);
            Bitmap opening = ((TextureView) a.playerView.getVideoSurfaceView()).getBitmap(32, 18);
            assertNotNull(opening);
            int pixel = opening.getPixel(16, 9);
            opening.recycle();
            assertTrue(
                "The paused native surface must contain the black opening",
                Color.red(pixel) < 35 && Color.green(pixel) < 35 && Color.blue(pixel) < 35);
            assertTrue(a.player.getCurrentPosition() < 200);
            return null;
          });
      long pausedPosition = ui(a -> a.player.getCurrentPosition());
      ResourcePreviewLoader.Result decoded = preview(resource);
      assertEquals(
          "The intro must not seed over the independently decoded preview",
          "视频画面",
          decoded.imageSource);
      assertRed(decoded.bitmap);
      ui(
          a -> {
            assertSame(original, a.player);
            assertEquals(
                "Reading a preview must not seek the playback decoder",
                pausedPosition,
                a.player.getCurrentPosition());
            assertFalse(a.player.getPlayWhenReady());
            return null;
          });

      AtomicInteger seeks = new AtomicInteger();
      long started = SystemClock.elapsedRealtime();
      ui(
          a -> {
            a.player.addListener(
                new Player.Listener() {
                  @Override
                  public void onPositionDiscontinuity(
                      Player.PositionInfo oldPosition,
                      Player.PositionInfo newPosition,
                      int reason) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet();
                  }
                });
            a.player.play();
            return null;
          });
      await(
          "Natural playback did not reach three seconds",
          10000,
          () -> ui(a -> a.player == original && a.player.getCurrentPosition() >= 3100));
      ui(
          a -> {
            a.player.pause();
            assertTrue(
                "Preview capture must not jump ahead of elapsed playback time",
                a.player.getCurrentPosition() <= SystemClock.elapsedRealtime() - started + 250);
            return null;
          });
      AtomicReference<ResourcePreviewLoader.Result> seeded = new AtomicReference<>();
      await(
          "Native capture did not replace the decoded preview after three seconds",
          5000,
          () -> {
            seeded.set(preview(resource));
            return "播放画面".equals(seeded.get().imageSource);
          });
      assertRed(seeded.get().bitmap);
      assertEquals("Preview handling must not issue any playback seek", 0, seeks.get());
      assertSame(original, ui(a -> a.player));
    } finally {
      if (scenario != null) scenario.close();
      SharedPreferences.Editor edit = prefs.edit();
      if (hadAuto) edit.putBoolean("auto_play", oldAuto);
      else edit.remove("auto_play");
      assertTrue(edit.commit());
    }
  }

  private ResourcePreviewLoader.Result preview(WebResource resource) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<ResourcePreviewLoader.Result> value = new AtomicReference<>();
    ui(
        a -> {
          a.previewLoader.load(
              resource,
              a.headersFor(resource),
              result -> {
                value.set(result);
                done.countDown();
              });
          return null;
        });
    assertTrue("Preview callback timed out", done.await(10, TimeUnit.SECONDS));
    return value.get();
  }

  private static void assertRed(Bitmap bitmap) {
    assertNotNull(bitmap);
    int pixel = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
    assertTrue("Expected the red scene after the black opening", Color.red(pixel) > 180);
    assertTrue(Color.green(pixel) < 60 && Color.blue(pixel) < 60);
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scenario.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }

  private interface Ready {
    boolean value() throws Exception;
  }

  private void await(String message, long timeoutMs, Ready ready) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeoutMs;
    do {
      if (ready.value()) return;
      SystemClock.sleep(50);
    } while (SystemClock.elapsedRealtime() < end);
    fail(message);
  }
}
