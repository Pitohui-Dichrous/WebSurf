package io.websurf.app;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.SeekBar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.Player;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real screen input against native controls; no synthetic controller callbacks. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class VideoControlsTest {
  private ActivityScenario<MainActivity> scenario;
  private String lastInputTarget = "video gesture";

  @Test
  public void nativeControlsRespectPauseLockTemporarySpeedAndSeek() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse("http://127.0.0.1:8765/?native-controls=" + System.nanoTime())));
    try {
      await(
          "Fixture media target missing",
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
            // Freeze the short fixture while Android rotates; begin interaction only after layout.
            a.player.pause();
            a.setPlayerMode(VideoChrome.Mode.FULLSCREEN);
            return null;
          });
      await(
          "Native player did not start",
          10000,
          () -> ui(a -> a.player != null && a.player.getPlaybackState() == Player.STATE_READY));
      awaitPresentation(scenario, VideoChrome.Mode.FULLSCREEN, Configuration.ORIENTATION_PORTRAIT);
      clickControl("播放");
      await("Initial native play click was ignored", 2000, () -> ui(a -> a.player.isPlaying()));
      clickControl("暂停");
      await("Native pause click was ignored", 2000, () -> ui(a -> !a.player.getPlayWhenReady()));
      clickControl("播放");
      await("Native play click was ignored", 2000, () -> ui(a -> a.player.getPlayWhenReady()));
      ui(
          a -> {
            a.player.setPlaybackSpeed(1.25f);
            return null;
          });
      float[] point = videoPoint();
      long hold = SystemClock.uptimeMillis();
      pointer(hold, MotionEvent.ACTION_DOWN, point[0], point[1]);
      try {
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100L);
        await(
            "Long press did not temporarily select 2x",
            1500,
            () -> ui(a -> Math.abs(a.player.getPlaybackParameters().speed - 2f) < .001f));
      } finally {
        pointer(hold, MotionEvent.ACTION_UP, point[0], point[1]);
      }
      await(
          "Releasing long press did not restore 1.25x",
          1500,
          () -> ui(a -> Math.abs(a.player.getPlaybackParameters().speed - 1.25f) < .001f));
      clickControl("暂停");
      await(
          "Native pause click after boost was ignored",
          1500,
          () -> ui(a -> !a.player.getPlayWhenReady()));
      clickControl("锁定播放控制");
      assertTrue(ui(a -> a.videoChrome.isLocked()));
      long pausedAt = ui(a -> a.player.getCurrentPosition());
      tap(point[0], point[1]);
      SystemClock.sleep(90);
      tap(point[0], point[1]);
      long lockedHold = SystemClock.uptimeMillis();
      pointer(lockedHold, MotionEvent.ACTION_DOWN, point[0], point[1]);
      try {
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100L);
        assertFalse("Locked double tap resumed playback", ui(a -> a.player.getPlayWhenReady()));
        assertEquals(
            "Locked long press changed speed",
            1.25f,
            ui(a -> a.player.getPlaybackParameters().speed),
            .001f);
      } finally {
        pointer(lockedHold, MotionEvent.ACTION_UP, point[0], point[1]);
      }
      assertEquals(pausedAt, ui(a -> a.player.getCurrentPosition()), 150);
      clickControl("解锁播放控制");
      assertFalse(ui(a -> a.videoChrome.isLocked()));

      // Drag the real SeekBar, then check the released thumb commits a seek without resuming.
      Rect seek =
          ui(
              a -> {
                View found = findDescription(a.videoChrome, "播放进度");
                assertTrue(found instanceof SeekBar);
                Rect rect = new Rect();
                assertTrue(found.getGlobalVisibleRect(rect));
                int[] screen = new int[2];
                found.getLocationOnScreen(screen);
                Rect screenRect =
                    new Rect(
                        screen[0],
                        screen[1],
                        screen[0] + found.getWidth(),
                        screen[1] + found.getHeight());
                lastInputTarget =
                    "control=播放进度, globalBBox="
                        + rect.toShortString()
                        + ", screenBBox="
                        + screenRect.toShortString()
                        + ", "
                        + windowDiagnostic(a);
                return screenRect;
              });
      float from = seek.left + seek.width() * .2f,
          to = seek.left + seek.width() * .75f,
          y = seek.exactCenterY();
      long drag = SystemClock.uptimeMillis();
      pointer(drag, MotionEvent.ACTION_DOWN, from, y);
      SystemClock.sleep(60);
      pointer(drag, MotionEvent.ACTION_MOVE, (from + to) / 2, y);
      SystemClock.sleep(60);
      pointer(drag, MotionEvent.ACTION_MOVE, to, y);
      pointer(drag, MotionEvent.ACTION_UP, to, y);
      await(
          "Releasing the seek thumb did not commit its position",
          2000,
          () -> ui(a -> a.player.getCurrentPosition() > a.player.getDuration() * .55));
      assertFalse(
          "Dragging the paused seek bar resumed playback", ui(a -> a.player.getPlayWhenReady()));
    } finally {
      if (scenario != null) scenario.close();
    }
  }

  /** Wait for Android configuration, insets and the actual native view, not a nominal delay. */
  static void awaitPresentation(
      ActivityScenario<MainActivity> scenario, VideoChrome.Mode mode, int restoredOrientation)
      throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 8000, stableSince = 0;
    AtomicReference<String> diagnostic = new AtomicReference<>("");
    do {
      AtomicReference<Boolean> ready = new AtomicReference<>(false);
      scenario.onActivity(
          a -> {
            int orientation =
                mode == VideoChrome.Mode.FULLSCREEN
                    ? Configuration.ORIENTATION_LANDSCAPE
                    : restoredOrientation;
            boolean settled =
                windowReady(a, orientation, mode == VideoChrome.Mode.FULLSCREEN)
                    && a.videoChrome != null
                    && a.playerMode == mode
                    && a.videoChrome.isLaidOut()
                    && !a.videoChrome.isLayoutRequested();
            if (settled) {
              int width = a.root.getWidth() - a.root.getPaddingLeft() - a.root.getPaddingRight();
              int height = a.root.getHeight() - a.root.getPaddingTop() - a.root.getPaddingBottom();
              if (mode == VideoChrome.Mode.FULLSCREEN) {
                settled =
                    a.videoChrome.getParent() == a.root
                        && a.videoChrome.getWidth() == width
                        && a.videoChrome.getHeight() == height
                        && a.videoChrome.getWidth() > a.videoChrome.getHeight();
              } else if (mode == VideoChrome.Mode.FLOATING) {
                settled =
                    a.videoChrome.getParent() == a.root
                        && a.videoChrome.getWidth() > 0
                        && a.videoChrome.getHeight() > 0
                        && a.videoChrome.getWidth() < width
                        && a.videoChrome.getHeight() < height
                        && a.videoChrome.getLeft() >= a.root.getPaddingLeft()
                        && a.videoChrome.getTop() >= a.root.getPaddingTop()
                        && a.videoChrome.getRight() <= a.root.getWidth() - a.root.getPaddingRight()
                        && a.videoChrome.getBottom()
                            <= a.root.getHeight() - a.root.getPaddingBottom();
              } else {
                VideoTarget target =
                    a.activeTarget == null ? null : a.current.videos.get(a.activeTarget.key());
                settled =
                    a.anchorClip != null
                        && a.videoChrome.getParent() == a.anchorClip
                        && a.anchorClip.getParent() == a.webContainer
                        && target != null
                        && target.viewportWidth > 0
                        && a.current.web.getWidth() == width
                        && !a.current.web.isLayoutRequested();
                if (settled) {
                  float scale = a.current.web.getWidth() / target.viewportWidth;
                  if (a.inlinePinned) {
                    int[] picture = new int[2], page = new int[2];
                    a.videoChrome.getLocationOnScreen(picture);
                    a.current.web.getLocationOnScreen(page);
                    settled =
                        Math.abs(picture[1] - page[1]) < 4
                            && a.videoChrome.getWidth() > 0
                            && a.videoChrome.getWidth() <= a.current.web.getWidth()
                            && a.videoChrome.getHeight() > 0
                            && a.videoChrome.getHeight() <= a.current.web.getHeight();
                  } else {
                    settled =
                        Math.abs(a.videoChrome.getWidth() - target.rect.width() * scale) < 4
                            && Math.abs(a.videoChrome.getHeight() - target.rect.height() * scale)
                                < 4;
                  }
                }
              }
            }
            ready.set(settled);
            diagnostic.set(
                "orientation="
                    + a.getResources().getConfiguration().orientation
                    + ", requested="
                    + a.getRequestedOrientation()
                    + ", root="
                    + a.root.getWidth()
                    + "x"
                    + a.root.getHeight()
                    + ", native="
                    + (a.videoChrome == null
                        ? "null"
                        : a.videoChrome.getWidth() + "x" + a.videoChrome.getHeight())
                    + ", "
                    + windowDiagnostic(a));
          });
      long now = SystemClock.elapsedRealtime();
      if (ready.get()) {
        if (stableSince == 0) stableSince = now;
        else if (now - stableSince >= 180) {
          if (mode == VideoChrome.Mode.FULLSCREEN) confirmFirstFullscreenTutorial(scenario);
          return;
        }
      } else stableSince = 0;
      SystemClock.sleep(60);
    } while (SystemClock.elapsedRealtime() < deadline);
    fail("Presentation did not settle for " + mode + " [" + diagnostic.get() + "]");
  }

  /** Confirm only Android's identified first-fullscreen introduction, using its own button. */
  private static void confirmFirstFullscreenTutorial(ActivityScenario<MainActivity> scenario)
      throws Exception {
    // Android displays this after the app's first immersive frame, sometimes a second later.
    // Callers keep the short fixture paused during this bounded observation period.
    long observeUntil = SystemClock.elapsedRealtime() + 2000;
    boolean confirmed = false;
    do {
      AccessibilityNodeInfo root =
          InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
      if (root != null) {
        try {
          // Android 14's recorded immersive_cling_title / android:id/ok window belongs to
          // the framework package "android" (see .tools/tutorial-diagnostic.log).
          String owner = String.valueOf(root.getPackageName());
          if (("android".equals(owner) || "com.android.systemui".equals(owner))
              && hasAccessibleText(root, "Viewing full screen")
              && hasAccessibleText(root, "To exit, swipe down from the top.")) {
            List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByText("GOT IT");
            try {
              for (AccessibilityNodeInfo button : buttons) {
                if (button.isVisibleToUser()
                    && button.isEnabled()
                    && button.isClickable()
                    && owner.contentEquals(
                        button.getPackageName() == null ? "" : button.getPackageName())
                    && "GOT IT".equals(normalizeText(button.getText()))) {
                  assertTrue(
                      "Android fullscreen introduction did not accept GOT IT",
                      button.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                  confirmed = true;
                  break;
                }
              }
            } finally {
              for (AccessibilityNodeInfo button : buttons) button.recycle();
            }
          }
        } finally {
          root.recycle();
        }
      }
      if (confirmed) break;
      SystemClock.sleep(80);
    } while (SystemClock.elapsedRealtime() < observeUntil);
    if (confirmed) {
      Bundle status = new Bundle();
      status.putString("stream", "\nConfirmed Android's first-fullscreen GOT IT introduction.\n");
      InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }
    long focusDeadline = SystemClock.elapsedRealtime() + 3000, focusedSince = 0;
    AtomicReference<String> diagnostic = new AtomicReference<>("");
    do {
      AtomicReference<Boolean> focused = new AtomicReference<>(false);
      scenario.onActivity(
          activity -> {
            focused.set(
                activity.hasWindowFocus() && activity.getWindow().getDecorView().hasWindowFocus());
            diagnostic.set(windowDiagnostic(activity));
          });
      long now = SystemClock.elapsedRealtime();
      if (focused.get()) {
        if (focusedSince == 0) focusedSince = now;
        else if (now - focusedSince >= 180) return;
      } else focusedSince = 0;
      SystemClock.sleep(60);
    } while (SystemClock.elapsedRealtime() < focusDeadline);
    dumpFullscreenWindows();
    fail("App did not regain focus after fullscreen preparation [" + diagnostic.get() + "]");
  }

  private static void dumpFullscreenWindows() {
    UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    AccessibilityServiceInfo info = automation.getServiceInfo();
    int previousFlags = info.flags;
    info.flags |=
        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
    automation.setServiceInfo(info);
    String appPackage = ApplicationProvider.getApplicationContext().getPackageName();
    StringBuilder output = new StringBuilder("\nFULLSCREEN_WINDOW_DIAGNOSTIC\n");
    try {
      AccessibilityNodeInfo active = automation.getRootInActiveWindow();
      output.append("activeWindowRoot\n");
      if (active == null) output.append("  null\n");
      else {
        try {
          dumpLocalWindowRoot(output, active, appPackage);
        } finally {
          active.recycle();
        }
      }
      List<AccessibilityWindowInfo> windows = automation.getWindows();
      for (AccessibilityWindowInfo window : windows) {
        Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        AccessibilityNodeInfo root = window.getRoot();
        try {
          output
              .append("window id=")
              .append(window.getId())
              .append(" type=")
              .append(window.getType())
              .append(" active=")
              .append(window.isActive())
              .append(" focused=")
              .append(window.isFocused())
              .append(" bounds=")
              .append(bounds.toShortString());
          if (root == null) output.append(" root=null\n");
          else {
            String pkg = String.valueOf(root.getPackageName());
            output.append(" package=").append(pkg);
            if (isLocalTestWindow(pkg, appPackage)) {
              output.append(" title=").append(window.getTitle()).append('\n');
              dumpLocalWindowRoot(output, root, appPackage);
            } else output.append(" [other window content omitted]\n");
          }
        } finally {
          if (root != null) root.recycle();
          window.recycle();
        }
      }
    } finally {
      info.flags = previousFlags;
      automation.setServiceInfo(info);
    }
    Bundle status = new Bundle();
    status.putString("stream", output.toString());
    InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
  }

  private static boolean isLocalTestWindow(String pkg, String appPackage) {
    return pkg.equals(appPackage) || pkg.equals("com.android.systemui") || pkg.equals("android");
  }

  private static void dumpLocalWindowRoot(
      StringBuilder output, AccessibilityNodeInfo root, String appPackage) {
    String pkg = String.valueOf(root.getPackageName());
    if (!isLocalTestWindow(pkg, appPackage)) {
      output.append("  package=").append(pkg).append(" [other window content omitted]\n");
      return;
    }
    dumpNode(output, root, "0", new int[] {0});
  }

  private static void dumpNode(
      StringBuilder output, AccessibilityNodeInfo node, String path, int[] count) {
    if (++count[0] > 100 || path.length() > 35) return;
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    output
        .append("  ")
        .append(path)
        .append(" package=")
        .append(node.getPackageName())
        .append(" class=")
        .append(node.getClassName())
        .append(" id=")
        .append(node.getViewIdResourceName())
        .append(" text=")
        .append(shortNodeText(node.getText()))
        .append(" description=")
        .append(shortNodeText(node.getContentDescription()))
        .append(" clickable=")
        .append(node.isClickable())
        .append(" enabled=")
        .append(node.isEnabled())
        .append(" visible=")
        .append(node.isVisibleToUser())
        .append(" bounds=")
        .append(bounds.toShortString())
        .append('\n');
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child != null) {
        try {
          dumpNode(output, child, path + "." + i, count);
        } finally {
          child.recycle();
        }
      }
    }
  }

  private static String shortNodeText(CharSequence text) {
    if (text == null) return "null";
    String value = text.toString().replace('\n', ' ').replace('\r', ' ');
    return value.length() > 160 ? value.substring(0, 160) + "…" : value;
  }

  private static boolean hasAccessibleText(AccessibilityNodeInfo root, String expected) {
    List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(expected);
    try {
      for (AccessibilityNodeInfo node : matches) {
        if (node.isVisibleToUser()
            && normalizeText(node.getText()).contains(normalizeText(expected))) return true;
      }
      return false;
    } finally {
      for (AccessibilityNodeInfo node : matches) node.recycle();
    }
  }

  private static String normalizeText(CharSequence text) {
    return text == null
        ? ""
        : text.toString().replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
  }

  static boolean windowReady(MainActivity activity, int orientation, boolean immersive) {
    if (activity.getResources().getConfiguration().orientation != orientation
        || !activity.root.isLaidOut()
        || activity.root.isLayoutRequested()
        || activity.root.getWidth() <= 0
        || activity.root.getHeight() <= 0) return false;
    if (orientation == Configuration.ORIENTATION_LANDSCAPE
        && activity.root.getWidth() <= activity.root.getHeight()) return false;
    if (orientation == Configuration.ORIENTATION_PORTRAIT
        && activity.root.getHeight() <= activity.root.getWidth()) return false;
    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(activity.root);
    if (insets == null) return false;
    boolean status = insets.isVisible(WindowInsetsCompat.Type.statusBars());
    boolean navigation = insets.isVisible(WindowInsetsCompat.Type.navigationBars());
    if (immersive ? (status || navigation) : (!status || !navigation)) return false;
    // Decor can consume system insets before dispatching them to root. Root padding therefore
    // cannot be compared to getRootWindowInsets(); inspect actual visible bounds instead.
    Rect visible = new Rect();
    return activity.root.isShown()
        && activity.root.getGlobalVisibleRect(visible)
        && visible.width() == activity.root.getWidth()
        && visible.height() == activity.root.getHeight();
  }

  private void clickControl(String description) {
    Rect target =
        ui(
            a -> {
              View view = findDescription(a.videoChrome, description);
              assertNotNull("Control not found: " + description, view);
              Rect rect = new Rect();
              assertTrue("Control hidden: " + description, view.getGlobalVisibleRect(rect));
              assertTrue("Control disabled: " + description, view.isEnabled());
              int[] screen = new int[2];
              view.getLocationOnScreen(screen);
              lastInputTarget =
                  "control="
                      + description
                      + ", globalBBox="
                      + rect.toShortString()
                      + ", screenBBox=["
                      + screen[0]
                      + ","
                      + screen[1]
                      + "]["
                      + (screen[0] + view.getWidth())
                      + ","
                      + (screen[1] + view.getHeight())
                      + "]"
                      + ", "
                      + windowDiagnostic(a);
              return new Rect(
                  screen[0], screen[1], screen[0] + view.getWidth(), screen[1] + view.getHeight());
            });
    tap(target.exactCenterX(), target.exactCenterY());
  }

  private float[] videoPoint() {
    return ui(
        a -> {
          int[] at = new int[2];
          a.videoChrome.getLocationOnScreen(at);
          return new float[] {
            at[0] + a.videoChrome.getWidth() * .67f, at[1] + a.videoChrome.getHeight() * .45f
          };
        });
  }

  private void tap(float x, float y) {
    long down = SystemClock.uptimeMillis();
    pointer(down, MotionEvent.ACTION_DOWN, x, y);
    SystemClock.sleep(45);
    pointer(down, MotionEvent.ACTION_UP, x, y);
  }

  private void pointer(long down, int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    try {
      InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
    } catch (RuntimeException failure) {
      String evidence =
          "action="
              + action
              + ", point=("
              + x
              + ","
              + y
              + "), "
              + lastInputTarget
              + ", atFailure="
              + ui(VideoControlsTest::windowDiagnostic);
      try {
        UiCaptureTest.shot(ApplicationProvider.getApplicationContext(), "failure-controls");
        evidence += ", screenshot=external-files/qa/failure-controls.png";
      } catch (Throwable captureFailure) {
        failure.addSuppressed(captureFailure);
        evidence += ", screenshotError=" + captureFailure;
      }
      Bundle status = new Bundle();
      status.putString("stream", "\nINPUT_DIAGNOSTIC: " + evidence + "\n");
      InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
      failure.addSuppressed(new IllegalStateException(evidence));
      throw failure;
    } finally {
      event.recycle();
    }
  }

  private static String windowDiagnostic(MainActivity activity) {
    int[] origin = new int[2];
    activity.root.getLocationOnScreen(origin);
    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(activity.root);
    return "rootOrigin=("
        + origin[0]
        + ","
        + origin[1]
        + ")"
        + ", rootBounds="
        + activity.root.getWidth()
        + "x"
        + activity.root.getHeight()
        + ", rootPadding=("
        + activity.root.getPaddingLeft()
        + ","
        + activity.root.getPaddingTop()
        + ","
        + activity.root.getPaddingRight()
        + ","
        + activity.root.getPaddingBottom()
        + ")"
        + ", windowFocus="
        + activity.hasWindowFocus()
        + ", decorFocus="
        + activity.getWindow().getDecorView().hasWindowFocus()
        + ", statusVisible="
        + (insets == null ? "unknown" : insets.isVisible(WindowInsetsCompat.Type.statusBars()))
        + ", navigationVisible="
        + (insets == null ? "unknown" : insets.isVisible(WindowInsetsCompat.Type.navigationBars()));
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
      SystemClock.sleep(60);
    } while (SystemClock.elapsedRealtime() < end);
    fail(message);
  }

  private static View findDescription(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findDescription(group.getChildAt(i), description);
        if (found != null) return found;
      }
    }
    return null;
  }
}
