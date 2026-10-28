package io.websurf.app;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.util.ArrayList;

/** Routes vertical gestures on the inline picture to the page without replacing its player. */
final class InlineVideoHost extends FrameLayout {
  private static final float DIRECTION_RATIO = 1.25f;
  private static final int MAX_PENDING_EVENTS = 24;
  private final WebView web;
  private final int touchSlop;
  private final int[] webLocation = new int[2];
  // Copies use screen coordinates: the host itself moves as soon as the page starts scrolling.
  private final ArrayList<MotionEvent> pending = new ArrayList<>();
  private VideoChrome gestureChrome;
  private MotionEvent lastForwarded;
  private boolean candidate;
  private boolean forwarding;
  private float downRawX, downRawY;

  InlineVideoHost(Context context, WebView web) {
    super(context);
    this.web = web;
    touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) {
      clearGesture(true);
      gestureChrome = findChrome();
      downRawX = event.getRawX();
      downRawY = event.getRawY();
      candidate =
          gestureChrome != null
              && gestureChrome.isPageScrollStartAllowed(downRawX, downRawY)
              && !gestureChrome.hasActivePlaybackGesture();
      if (candidate) remember(event);
    } else if (forwarding) {
      MotionEvent screen = screenCopy(event);
      try {
        dispatchToPage(screen);
      } finally {
        screen.recycle();
      }
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        clearGesture(false);
      }
      return true;
    } else if (candidate) {
      if (action == MotionEvent.ACTION_POINTER_DOWN
          || event.getPointerCount() != 1
          || gestureChrome == null
          || gestureChrome.getParent() != this
          || gestureChrome.hasActivePlaybackGesture()) {
        abandonCandidate();
      } else if (action == MotionEvent.ACTION_MOVE) {
        float dx = Math.abs(event.getRawX() - downRawX);
        float dy = Math.abs(event.getRawY() - downRawY);
        remember(event);
        if (dx > touchSlop && dx > dy * DIRECTION_RATIO) {
          // Once the user starts seeking, later vertical drift cannot change the gesture's owner.
          abandonCandidate();
        } else if (dy > touchSlop && dy > dx * DIRECTION_RATIO) {
          MotionEvent cancel = MotionEvent.obtain(event);
          cancel.setAction(MotionEvent.ACTION_CANCEL);
          try {
            // End the player's tap/long-press recognition before replaying the original DOWN.
            super.dispatchTouchEvent(cancel);
          } finally {
            cancel.recycle();
          }
          forwarding = true;
          candidate = false;
          for (MotionEvent screen : pending) dispatchToPage(screen);
          recyclePending();
          return true;
        }
      }
    }
    boolean handled = super.dispatchTouchEvent(event);
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      clearGesture(false);
    }
    return handled;
  }

  @Override
  public void onViewRemoved(View child) {
    if (child == gestureChrome) clearGesture(true);
    super.onViewRemoved(child);
  }

  @Override
  protected void onDetachedFromWindow() {
    clearGesture(true);
    super.onDetachedFromWindow();
  }

  private VideoChrome findChrome() {
    for (int i = getChildCount() - 1; i >= 0; i--) {
      View child = getChildAt(i);
      if (child instanceof VideoChrome && child.isShown()) return (VideoChrome) child;
    }
    return null;
  }

  private void remember(MotionEvent event) {
    // Keep the real DOWN and recent samples, including their original timestamps and history.
    if (pending.size() >= MAX_PENDING_EVENTS) pending.remove(1).recycle();
    pending.add(screenCopy(event));
  }

  private static MotionEvent screenCopy(MotionEvent event) {
    MotionEvent screen = MotionEvent.obtain(event);
    screen.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY());
    return screen;
  }

  private void dispatchToPage(MotionEvent screen) {
    web.getLocationOnScreen(webLocation);
    MotionEvent local = MotionEvent.obtain(screen);
    local.offsetLocation(-webLocation[0], -webLocation[1]);
    try {
      // WebView owns scrolling, velocity tracking and fling; no synthetic scrollBy/timer loop.
      web.dispatchTouchEvent(local);
    } finally {
      local.recycle();
    }
    if (lastForwarded != null) lastForwarded.recycle();
    lastForwarded = MotionEvent.obtain(screen);
  }

  private void abandonCandidate() {
    candidate = false;
    recyclePending();
  }

  private void recyclePending() {
    for (MotionEvent event : pending) event.recycle();
    pending.clear();
  }

  private void clearGesture(boolean cancelPage) {
    if (cancelPage && forwarding && lastForwarded != null) {
      MotionEvent cancel =
          MotionEvent.obtain(
              lastForwarded.getDownTime(),
              SystemClock.uptimeMillis(),
              MotionEvent.ACTION_CANCEL,
              lastForwarded.getX(),
              lastForwarded.getY(),
              lastForwarded.getMetaState());
      try {
        dispatchToPage(cancel);
      } finally {
        cancel.recycle();
      }
    }
    candidate = false;
    forwarding = false;
    gestureChrome = null;
    recyclePending();
    if (lastForwarded != null) {
      lastForwarded.recycle();
      lastForwarded = null;
    }
  }
}
