package io.websurf.app;

import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;

/** Moves small marks inside their original touch targets, in whole rendering pixels. */
final class ChromePixelShift {
  static final int MAX_OFFSET_PX = 2;
  static final long INTERVAL_MS = 60_000L;

  private static final int[][] OFFSETS = {
    {0, 0},
    {1, -1},
    {MAX_OFFSET_PX, 0},
    {1, 1},
    {0, MAX_OFFSET_PX},
    {-1, 1},
    {-MAX_OFFSET_PX, 0},
    {-1, -1},
    {0, -MAX_OFFSET_PX}
  };

  // These buttons have no scrollable content. Android also delivers scrollTo events
  // asynchronously on older versions, after the decorative move has finished.
  private static final View.AccessibilityDelegate NON_SCROLLING_CONTROL =
      new View.AccessibilityDelegate() {
        @Override
        public void sendAccessibilityEventUnchecked(View host, AccessibilityEvent event) {
          if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_SCROLLED)
            super.sendAccessibilityEventUnchecked(host, event);
        }
      };

  private final TextView mark;
  private final LinearLayout navigation;
  private final View tabs, tabMark, downloads, badge;
  private boolean running;
  private final Runnable tick = this::tick;

  ChromePixelShift(
      TextView mark, LinearLayout navigation, View tabs, View tabMark, View downloads, View badge) {
    this.mark = mark;
    this.navigation = navigation;
    this.tabs = tabs;
    this.tabMark = tabMark;
    this.downloads = downloads;
    this.badge = badge;
    ignoreDecorativeScroll(mark);
    for (int i = 0; i < navigation.getChildCount(); i++) {
      View button = navigation.getChildAt(i);
      if (button instanceof ImageButton) ignoreDecorativeScroll(button);
    }
  }

  private static void ignoreDecorativeScroll(View button) {
    if (!ViewCompat.hasAccessibilityDelegate(button))
      button.setAccessibilityDelegate(NON_SCROLLING_CONTROL);
  }

  void start() {
    if (running) return;
    running = true;
    mark.post(tick);
  }

  void stop() {
    running = false;
    mark.removeCallbacks(tick);
  }

  private void tick() {
    if (!running) return;
    long uptime = SystemClock.uptimeMillis();
    update(uptime);
    mark.postDelayed(tick, INTERVAL_MS - uptime % INTERVAL_MS);
  }

  void update(long uptimeMillis) {
    if (!running || mark.getWindowVisibility() != View.VISIBLE) return;
    int[] point = OFFSETS[(int) ((uptimeMillis / INTERVAL_MS) % OFFSETS.length)];
    int x = point[0], y = point[1];
    if (mark.isShown()) {
      if (!mark.isPressed()) mark.scrollTo(-x, -y);
      if (!downloads.isPressed()) translate(badge, x, y);
    }
    if (navigation.isShown()) {
      for (int i = 0; i < navigation.getChildCount(); i++) {
        View button = navigation.getChildAt(i);
        if (button instanceof ImageButton && !button.isPressed()) button.scrollTo(-x, -y);
      }
      if (!tabs.isPressed()) translate(tabMark, x, y);
    }
  }

  private static void translate(View decoration, int x, int y) {
    decoration.setTranslationX(x);
    decoration.setTranslationY(y);
  }
}
