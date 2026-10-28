package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real pointer streams against the local scrolling page, WebView and the shared native player. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class ScrollPlaybackTest {
  private ActivityScenario<MainActivity> scenario;

  @Before
  public void openScrollingVideo() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse("http://127.0.0.1:8765/scroll.html?run=" + System.nanoTime())));
    await(
        "Scrolling fixture video was not discovered",
        10000,
        () ->
            ui(
                a ->
                    a.current != null
                        && a.current.videos.values().stream()
                            .anyMatch(t -> t.url.contains("sample-360.mp4") && t.height == 360)));
    ui(
        a -> {
          VideoTarget target =
              a.current.videos.values().stream()
                  .filter(t -> t.url.contains("sample-360.mp4") && t.height == 360)
                  .findFirst()
                  .orElseThrow();
          a.openTarget(a.current, target, true);
          a.player.setRepeatMode(Player.REPEAT_MODE_ONE);
          return null;
        });
    await(
        "Inline player did not become ready",
        10000,
        () ->
            ui(
                a ->
                    a.player != null
                        && a.player.isPlaying()
                        && a.playerMode == VideoChrome.Mode.INLINE
                        && a.anchorClip instanceof InlineVideoHost
                        && a.videoChrome.getHeight() > 0
                        && a.videoChrome.isShown()
                        && a.hasWindowFocus()
                        && !a.current.web.isLayoutRequested()));
  }

  @After
  public void closeFixture() {
    if (scenario != null) scenario.close();
  }

  @Test
  public void nativeScrollMovesPictureBeforeAnyJavascriptCallbackCanRun() {
    ui(
        a -> {
          VideoTarget measured = a.current.videos.get(a.activeTarget.key());
          assertNotNull(measured);
          assertTrue(measured.nativeScrollFollow);
          long revision = measured.layoutRevision;
          int originalScroll = a.current.web.getScrollY();
          float originalY = a.anchorClip.getTranslationY() + a.videoChrome.getTranslationY();
          ExoPlayer originalPlayer = a.player;
          try {
            // This entire loop is one main-thread callback. Observer messages and posted work
            // cannot run between scrollTo() and the assertion, so a 200 ms poll cannot pass.
            for (int offset : new int[] {8, 19, 37, 51}) {
              a.current.web.scrollTo(a.current.web.getScrollX(), originalScroll + dp(offset));
              int moved = a.current.web.getScrollY() - originalScroll;
              assertEquals(dp(offset), moved);
              assertEquals(
                  "Native scroll did not move the picture in its own callback",
                  originalY - moved,
                  a.anchorClip.getTranslationY() + a.videoChrome.getTranslationY(),
                  1f);
              assertSame(measured, a.current.videos.get(a.activeTarget.key()));
              assertEquals(revision, measured.layoutRevision);
              assertSame(originalPlayer, a.player);
              assertFalse(a.inlinePinned);
            }
          } finally {
            a.current.web.scrollTo(a.current.web.getScrollX(), originalScroll);
          }
          return null;
        });
  }

  @Test
  public void draggingPicturePinsAndRestoresTheSamePlayer() throws Exception {
    ExoPlayer player = ui(a -> a.player);
    View surface = ui(a -> a.playerView.getVideoSurfaceView());
    Rect initial = ui(a -> screenBounds(a.videoChrome));
    Rect page = ui(a -> screenBounds(a.current.web));
    assertTrue("Fixture should begin below the page's top", initial.top > page.top + dp(80));
    UiCaptureTest.shot(ApplicationProvider.getApplicationContext(), "12-scroll-inline");
    float[] start = picturePoint();
    drag(start[0], start[1], start[0], page.top + dp(8), 440, true);
    await(
        "Dragging the picture did not scroll the page",
        2500,
        () -> ui(a -> a.current.web.getScrollY() > dp(80)));
    await("Player did not pin at the top", 2500, () -> ui(a -> a.inlinePinned));
    assertEquals(VideoChrome.Mode.INLINE, ui(a -> a.playerMode));
    assertSame(player, ui(a -> a.player));
    assertSame(surface, ui(a -> a.playerView.getVideoSurfaceView()));
    Rect pinned = ui(a -> screenBounds(a.videoChrome));
    assertEquals("Pinned picture was hidden behind browser chrome", page.top, pinned.top, dp(2));
    assertTrue("Player did not expand with continued scrolling", pinned.width() > initial.width());
    UiCaptureTest.shot(ApplicationProvider.getApplicationContext(), "13-scroll-pinned");

    start = picturePoint();
    drag(start[0], start[1], start[0], page.bottom - dp(20), 520, true);
    await(
        "Reverse drag did not restore page position and inline geometry",
        3000,
        () -> ui(a -> a.current.web.getScrollY() <= dp(2) && !a.inlinePinned));
    Rect restored = ui(a -> screenBounds(a.videoChrome));
    assertEquals(initial.top, restored.top, dp(3));
    assertEquals(initial.width(), restored.width(), dp(3));
    assertSame(player, ui(a -> a.player));
    assertSame(surface, ui(a -> a.playerView.getVideoSurfaceView()));
    assertTrue("Scrolling stopped playback", ui(a -> a.player.getPlayWhenReady()));
  }

  @Test
  public void pictureSwipeKeepsWebViewFlingAfterRelease() throws Exception {
    ExoPlayer player = ui(a -> a.player);
    Rect page = ui(a -> screenBounds(a.current.web));
    float[] start = picturePoint();
    int initialScroll = ui(a -> a.current.web.getScrollY());
    float endY = page.top + dp(8);
    float fingerTravel = start[1] - endY;
    assertTrue("Fixture does not leave room for an upward swipe", fingerTravel > dp(80));
    long down = SystemClock.uptimeMillis();
    pointer(down, MotionEvent.ACTION_DOWN, start[0], start[1]);
    int beforeRelease = initialScroll;
    float sampledY = start[1];
    for (int i = 1; i <= 8; i++) {
      SystemClock.sleep(14);
      float y = start[1] + (endY - start[1]) * i / 8f;
      pointer(down, MotionEvent.ACTION_MOVE, start[0], y);
      if (i == 6) {
        // Sample while the finger is still down, then send two fresh movement samples before UP.
        // A cross-thread read between the final MOVE and UP could itself drain the velocity.
        beforeRelease = ui(a -> a.current.web.getScrollY());
        sampledY = y;
      }
    }
    pointer(down, MotionEvent.ACTION_UP, start[0], endY);
    // sendPointerSync(UP) and the next main-thread read may return after some/all fling frames.
    // Compare against the whole physical finger displacement instead of that late read. Also
    // bound queued motion after the pre-release sample, so deferred dragging cannot count as fling.
    int minimumFlingPosition =
        Math.max(
                initialScroll + (int) Math.ceil(fingerTravel),
                beforeRelease + (int) Math.ceil(sampledY - endY))
            + dp(12);
    await(
        "WebView did not move beyond the complete finger travel after release"
            + " [start="
            + initialScroll
            + ", preRelease="
            + beforeRelease
            + ", fingerTravel="
            + fingerTravel
            + ", required="
            + minimumFlingPosition
            + "]",
        2000,
        () -> ui(a -> a.current.web.getScrollY() > minimumFlingPosition));
    Bundle evidence = new Bundle();
    evidence.putString(
        "stream",
        "\nFLING_EVIDENCE start="
            + initialScroll
            + " preRelease="
            + beforeRelease
            + " fingerTravelPx="
            + fingerTravel
            + " requiredScroll="
            + minimumFlingPosition
            + " observedScroll="
            + ui(a -> a.current.web.getScrollY())
            + "\n");
    InstrumentationRegistry.getInstrumentation().sendStatus(2, evidence);
    assertSame(player, ui(a -> a.player));
    assertEquals(VideoChrome.Mode.INLINE, ui(a -> a.playerMode));
    assertTrue(ui(a -> a.inlinePinned));
  }

  @Test
  public void inlineSeekButtonsAndHeldSpeedKeepTheirGestures() throws Exception {
    ui(
        a -> {
          a.player.setPlaybackSpeed(1.25f);
          a.videoChrome.showControls();
          return null;
        });
    float[] point = picturePoint();
    int initialScroll = ui(a -> a.current.web.getScrollY());
    long hold = SystemClock.uptimeMillis();
    pointer(hold, MotionEvent.ACTION_DOWN, point[0], point[1]);
    try {
      SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100L);
      await(
          "Inline long press did not enable 2x",
          1200,
          () -> ui(a -> Math.abs(a.player.getPlaybackParameters().speed - 2f) < .001f));
      pointer(hold, MotionEvent.ACTION_MOVE, point[0], point[1] - dp(40));
      assertEquals(
          "A held speed gesture was stolen by page scrolling",
          initialScroll,
          (int) ui(a -> a.current.web.getScrollY()));
      assertEquals(2f, (float) ui(a -> a.player.getPlaybackParameters().speed), .001f);
    } finally {
      pointer(hold, MotionEvent.ACTION_UP, point[0], point[1] - dp(40));
    }
    await(
        "Held speed did not restore 1.25x",
        1200,
        () -> ui(a -> Math.abs(a.player.getPlaybackParameters().speed - 1.25f) < .001f));
    ui(
        a -> {
          a.videoChrome.showControls();
          return null;
        });
    clickControl("暂停");
    await("Inline pause button was intercepted", 1200, () -> ui(a -> !a.player.getPlayWhenReady()));
    ui(
        a -> {
          a.player.seekTo(0);
          return null;
        });
    await(
        "Player did not return to start", 2000, () -> ui(a -> a.player.getCurrentPosition() < 100));
    point = picturePoint();
    Rect picture = ui(a -> screenBounds(a.videoChrome));
    drag(point[0], point[1], picture.right - dp(12), point[1], 260, true);
    await(
        "Horizontal picture gesture did not seek",
        2000,
        () -> ui(a -> a.player.getCurrentPosition() > a.player.getDuration() * .15));
    assertEquals(initialScroll, (int) ui(a -> a.current.web.getScrollY()));
    assertFalse(ui(a -> a.player.getPlayWhenReady()));

    // A vertical drift that starts on the real seek control must not become a page gesture.
    Rect seek = ui(a -> screenBounds(findDescription(a.videoChrome, "播放进度")));
    drag(
        seek.exactCenterX(),
        seek.exactCenterY(),
        seek.exactCenterX(),
        seek.exactCenterY() - dp(70),
        220,
        true);
    assertEquals(
        "SeekBar movement scrolled the page",
        initialScroll,
        (int) ui(a -> a.current.web.getScrollY()));
  }

  private float[] picturePoint() {
    return ui(
        a -> {
          Rect picture = screenBounds(a.videoChrome);
          return new float[] {picture.exactCenterX(), picture.exactCenterY()};
        });
  }

  private void clickControl(String description) {
    Rect bounds = ui(a -> screenBounds(findDescription(a.videoChrome, description)));
    long down = SystemClock.uptimeMillis();
    pointer(down, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY());
    SystemClock.sleep(45);
    pointer(down, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY());
  }

  private void drag(float x1, float y1, float x2, float y2, int duration, boolean settle) {
    long down = SystemClock.uptimeMillis();
    pointer(down, MotionEvent.ACTION_DOWN, x1, y1);
    int steps = settle ? 18 : 8;
    for (int i = 1; i <= steps; i++) {
      SystemClock.sleep(Math.max(1, duration / steps));
      float progress = i / (float) steps;
      pointer(down, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * progress, y1 + (y2 - y1) * progress);
    }
    if (settle) {
      SystemClock.sleep(180);
      pointer(down, MotionEvent.ACTION_MOVE, x2, y2);
    }
    pointer(down, MotionEvent.ACTION_UP, x2, y2);
  }

  private void pointer(long down, int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    try {
      InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
    } finally {
      event.recycle();
    }
  }

  private static Rect screenBounds(View view) {
    assertNotNull("Expected visible view", view);
    assertTrue("View was not shown", view.isShown());
    int[] screen = new int[2];
    view.getLocationOnScreen(screen);
    return new Rect(
        screen[0], screen[1], screen[0] + view.getWidth(), screen[1] + view.getHeight());
  }

  private static View findDescription(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findDescription(group.getChildAt(i), description);
        if (found != null) return found;
      }
    }
    return null;
  }

  private int dp(float value) {
    return Math.round(
        value
            * ApplicationProvider.<Context>getApplicationContext()
                .getResources()
                .getDisplayMetrics()
                .density);
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scenario.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }

  private interface Ready {
    boolean value() throws Exception;
  }

  private void await(String message, long timeout, Ready condition) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeout;
    do {
      if (condition.value()) return;
      SystemClock.sleep(50);
    } while (SystemClock.elapsedRealtime() < end);
    fail(
        message
            + ui(
                a ->
                    " [scrollY="
                        + a.current.web.getScrollY()
                        + ", pinned="
                        + a.inlinePinned
                        + ", mode="
                        + a.playerMode
                        + "]"));
  }
}
