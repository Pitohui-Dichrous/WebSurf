package io.websurf.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

/** Both edges of the browser move with the same drag, leaving the middle to the WebView. */
final class ScrollControls {
  final LinearLayout header, footer;
  private final int slop;
  private float hidden, startX, startY, lastY;
  private boolean tracking, vertical, hiding;
  private ValueAnimator settle;

  ScrollControls(Context context) {
    header = group(context);
    footer = group(context);
    slop = ViewConfiguration.get(context).getScaledTouchSlop();
  }

  private static LinearLayout group(Context context) {
    LinearLayout group = new LinearLayout(context);
    group.setOrientation(LinearLayout.VERTICAL);
    return group;
  }

  void reveal() {
    tracking = false;
    cancelSettle();
    position(0);
  }

  // Observe the WebView's own pointer stream, including drags forwarded by InlineVideoHost.
  // Raw coordinates stay stable when reclaiming the header moves the WebView under the finger.
  void observe(MotionEvent event) {
    float y = event.getRawY();
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        cancelSettle();
        startX = event.getRawX();
        startY = lastY = y;
        tracking = true;
        vertical = false;
        break;
      case MotionEvent.ACTION_MOVE:
        if (!tracking || event.getPointerCount() != 1) break;
        if (!vertical) {
          float dx = Math.abs(event.getRawX() - startX);
          float dy = Math.abs(y - startY);
          if (dx > slop && dx > dy) {
            finish();
            break;
          }
          if (dy <= slop || dy < dx * 1.25f) break;
          vertical = true;
          lastY = startY + Math.copySign(slop, y - startY);
        }
        float delta = lastY - y;
        int travel = Math.max(header.getHeight(), footer.getHeight());
        if (delta != 0 && travel > 0) {
          hiding = delta > 0;
          position(Math.max(0, Math.min(1, hidden + delta / travel)));
        }
        lastY = y;
        break;
      case MotionEvent.ACTION_POINTER_DOWN:
      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_UP:
        finish();
        break;
      default:
        break;
    }
  }

  private void finish() {
    if (!tracking) return;
    tracking = false;
    if (hidden == 0 || hidden == 1) return;
    float target = (vertical ? hiding : hidden >= .5f) ? 1f : 0f;
    if (!ValueAnimator.areAnimatorsEnabled()) {
      position(target);
      return;
    }
    settle = ValueAnimator.ofFloat(hidden, target);
    settle.setInterpolator(new DecelerateInterpolator());
    settle.setDuration(Math.max(1, Math.round(180 * Math.abs(target - hidden))));
    settle.addUpdateListener(frame -> position((float) frame.getAnimatedValue()));
    settle.start();
  }

  private void cancelSettle() {
    if (settle != null) settle.cancel();
    settle = null;
  }

  private void position(float fraction) {
    if (hidden == fraction) return;
    hidden = fraction;
    moveEdge(header, true);
    moveEdge(footer, false);
  }

  private void moveEdge(View group, boolean top) {
    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) group.getLayoutParams();
    int offset = -Math.round(group.getHeight() * hidden);
    if ((top ? params.topMargin : params.bottomMargin) != offset) {
      if (top) params.topMargin = offset;
      else params.bottomMargin = offset;
      group.requestLayout();
    }
    group.setVisibility(hidden == 1f ? View.INVISIBLE : View.VISIBLE);
  }
}
