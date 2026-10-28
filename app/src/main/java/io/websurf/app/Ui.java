package io.websurf.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

/** Shared native UI tokens, adapted from the user's MediaMaster visual system. */
public final class Ui {
  public static int BG = 0xfff7f8f5;
  public static int SURFACE = 0xffedf0eb;
  public static int BORDER = 0xffdce1da;
  public static int TEXT = 0xff272637;
  public static int MUTED = 0xff62666b;
  public static int SOFT_ACCENT = 0xffe8f0e9;
  public static int CONTROL = 0xff145f39;
  public static final int ACCENT = 0xff145f39;
  public static final int RED = 0xffb3261e;
  private static boolean dark;
  private static final PathInterpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);

  private Ui() {}

  public static boolean isDark(Context context) {
    return context
        .getSharedPreferences("appearance", Context.MODE_PRIVATE)
        .getBoolean("dark", false);
  }

  public static void setDark(Context context, boolean enabled) {
    context
        .getSharedPreferences("appearance", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("dark", enabled)
        .apply();
    configure(context);
  }

  public static void configure(Context context) {
    dark = isDark(context);
    BG = dark ? 0xff1c1c1c : 0xfff7f8f5;
    SURFACE = dark ? 0xff2c2c2c : 0xffedf0eb;
    BORDER = dark ? 0xff444444 : 0xffdce1da;
    TEXT = dark ? 0xfff5f5f5 : 0xff272637;
    MUTED = dark ? 0xffc2c2c2 : 0xff62666b;
    SOFT_ACCENT = dark ? 0xff2a3d35 : 0xffe8f0e9;
    CONTROL = dark ? 0xfff5f5f5 : ACCENT;
  }

  public static int dp(Context context, float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  public static TextView text(Context context, String value, float size, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    view.setGravity(Gravity.CENTER_VERTICAL);
    return view;
  }

  public static GradientDrawable shape(Context context, int color, int radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(context, radius));
    return drawable;
  }

  public static void ripple(View view, int color, int radius) {
    Context context = view.getContext();
    view.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(dark ? 0x24ffffff : 0x1f145f39),
            shape(context, color, radius),
            shape(context, Color.WHITE, radius)));
    view.setClickable(true);
    view.setFocusable(true);
  }

  public static ImageButton icon(Context context, String kind, String label) {
    ImageButton button = new ImageButton(context);
    button.setImageDrawable(new Icon(kind, TEXT));
    button.setContentDescription(label);
    button.setTooltipText(label);
    button.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
    button.setMinimumWidth(dp(context, 48));
    button.setMinimumHeight(dp(context, 48));
    button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    button.setElevation(0);
    button.setStateListAnimator(null);
    ripple(button, Color.TRANSPARENT, 8);
    button.setImageTintList(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {(TEXT & 0x00ffffff) | 0x66000000, TEXT}));
    return button;
  }

  public static Button button(Context context, String label, boolean primary) {
    Button button = new PressButton(context);
    button.setText(label);
    button.setTextSize(14);
    button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    button.setAllCaps(false);
    button.setGravity(Gravity.CENTER);
    button.setIncludeFontPadding(false);
    button.setMinHeight(dp(context, 48));
    button.setMinimumHeight(dp(context, 48));
    button.setMinWidth(dp(context, 80));
    button.setMinimumWidth(dp(context, 80));
    button.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
    button.setElevation(0);
    button.setStateListAnimator(null);
    int foreground = primary ? Color.WHITE : TEXT;
    button.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {(foreground & 0x00ffffff) | 0x77000000, foreground}));
    button.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(primary || dark ? 0x30ffffff : 0x1f145f39),
            shape(context, primary ? ACCENT : SURFACE, 8),
            shape(context, Color.WHITE, 8)));
    return button;
  }

  /** A short reveal for a newly visible panel; no layout properties are animated. */
  public static void enter(View view) {
    view.animate().cancel();
    if (!ValueAnimator.areAnimatorsEnabled()) {
      view.setAlpha(1f);
      view.setTranslationY(0);
      return;
    }
    view.setAlpha(0f);
    view.setTranslationY(dp(view.getContext(), 8));
    view.animate().alpha(1f).translationY(0).setDuration(180).setInterpolator(EASE_OUT).start();
  }

  /** Pointer-only press feedback; keyboard focus and click semantics remain native. */
  private static final class PressButton extends Button {
    private float downX, downY;
    private boolean canceled;
    private final int slop;

    PressButton(Context context) {
      super(context);
      slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    private void scale(float value, int duration) {
      animate().cancel();
      if (!ValueAnimator.areAnimatorsEnabled()) {
        setScaleX(1f);
        setScaleY(1f);
      } else {
        animate()
            .scaleX(value)
            .scaleY(value)
            .setDuration(duration)
            .setInterpolator(EASE_OUT)
            .start();
      }
    }

    @Override
    public boolean performClick() {
      return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          canceled = false;
          downX = event.getX();
          downY = event.getY();
          boolean handled = super.onTouchEvent(event);
          if (handled && isEnabled()) scale(.95f, 100);
          return handled;
        case MotionEvent.ACTION_POINTER_DOWN:
        case MotionEvent.ACTION_MOVE:
          if (canceled) return true;
          float dx = event.getX() - downX, dy = event.getY() - downY;
          if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
              || dx * dx + dy * dy > slop * slop) {
            canceled = true;
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.onTouchEvent(cancel);
            cancel.recycle();
            scale(1f, 120);
            return true;
          }
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          scale(1f, 120);
          if (canceled) {
            canceled = false;
            return true;
          }
          break;
        default:
          break;
      }
      return super.onTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
      animate().cancel();
      setScaleX(1f);
      setScaleY(1f);
      setPressed(false);
      super.onDetachedFromWindow();
    }
  }

  /** Original 24-unit line drawings, with a shared 2-unit rounded stroke. */
  public static final class Icon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ColorStateList tint;
    private final String kind;
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public Icon(String kind, int color) {
      this.kind = kind;
      paint.setColor(color);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(2f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2) {
      canvas.drawLine(x1, y1, x2, y2, paint);
    }

    private void chain(Canvas canvas, float... points) {
      path.reset();
      path.moveTo(points[0], points[1]);
      for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
      canvas.drawPath(path, paint);
    }

    private void roundRect(
        Canvas canvas, float left, float top, float right, float bottom, float radius) {
      rect.set(left, top, right, bottom);
      canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @Override
    public void draw(Canvas canvas) {
      Rect bounds = getBounds();
      canvas.save();
      float size = Math.min(bounds.width(), bounds.height());
      canvas.translate(
          bounds.left + (bounds.width() - size) / 2f, bounds.top + (bounds.height() - size) / 2f);
      canvas.scale(size / 24f, size / 24f);
      switch (kind) {
        case "back":
          chain(canvas, 15, 5, 8, 12, 15, 19);
          break;
        case "forward":
          chain(canvas, 9, 5, 16, 12, 9, 19);
          break;
        case "home":
          chain(canvas, 3, 10, 12, 3, 21, 10);
          chain(canvas, 5, 9, 5, 21, 10, 21, 10, 15, 14, 15, 14, 21, 19, 21, 19, 9);
          break;
        case "refresh":
          rect.set(4, 4, 20, 20);
          canvas.drawArc(rect, 40, 285, false, paint);
          chain(canvas, 20, 3, 20, 9, 14, 9);
          break;
        case "close":
          line(canvas, 6, 6, 18, 18);
          line(canvas, 18, 6, 6, 18);
          break;
        case "more":
          canvas.drawCircle(5, 12, .65f, paint);
          canvas.drawCircle(12, 12, .65f, paint);
          canvas.drawCircle(19, 12, .65f, paint);
          break;
        case "download":
          line(canvas, 12, 3, 12, 15);
          chain(canvas, 7, 10, 12, 15, 17, 10);
          chain(canvas, 4, 15, 4, 21, 20, 21, 20, 15);
          break;
        case "video":
          roundRect(canvas, 3, 5, 21, 19, 2);
          chain(canvas, 10, 9, 15, 12, 10, 15, 10, 9);
          break;
        case "play":
          chain(canvas, 8, 4, 20, 12, 8, 20, 8, 4);
          break;
        case "pause":
          line(canvas, 8, 5, 8, 19);
          line(canvas, 16, 5, 16, 19);
          break;
        case "tabs":
          roundRect(canvas, 7, 7, 21, 21, 2);
          chain(canvas, 3, 17, 3, 3, 17, 3);
          break;
        case "settings":
          canvas.drawCircle(12, 12, 6, paint);
          canvas.drawCircle(12, 12, 2, paint);
          for (int i = 0; i < 8; i++) {
            double angle = Math.PI * i / 4;
            line(
                canvas,
                12 + 7 * (float) Math.cos(angle),
                12 + 7 * (float) Math.sin(angle),
                12 + 9 * (float) Math.cos(angle),
                12 + 9 * (float) Math.sin(angle));
          }
          break;
        case "add":
          line(canvas, 12, 5, 12, 19);
          line(canvas, 5, 12, 19, 12);
          break;
        case "link":
          canvas.save();
          canvas.rotate(-45, 12, 12);
          roundRect(canvas, 8, 2, 16, 13, 4);
          roundRect(canvas, 8, 11, 16, 22, 4);
          canvas.restore();
          break;
        case "check":
          chain(canvas, 4, 12, 9, 17, 20, 6);
          break;
        case "share":
          canvas.drawCircle(6, 12, 3, paint);
          canvas.drawCircle(18, 5, 3, paint);
          canvas.drawCircle(18, 19, 3, paint);
          line(canvas, 8.6f, 10.5f, 15.4f, 6.5f);
          line(canvas, 8.6f, 13.5f, 15.4f, 17.5f);
          break;
        case "expand":
          chain(canvas, 8, 3, 3, 3, 3, 8);
          chain(canvas, 16, 3, 21, 3, 21, 8);
          chain(canvas, 3, 16, 3, 21, 8, 21);
          chain(canvas, 16, 21, 21, 21, 21, 16);
          break;
        case "minimize":
          chain(canvas, 3, 8, 8, 8, 8, 3);
          chain(canvas, 16, 3, 16, 8, 21, 8);
          chain(canvas, 3, 16, 8, 16, 8, 21);
          chain(canvas, 16, 21, 16, 16, 21, 16);
          break;
        default:
          canvas.drawCircle(12, 12, 9, paint);
          canvas.drawOval(8, 3, 16, 21, paint);
          line(canvas, 3, 12, 21, 12);
          break;
      }
      canvas.restore();
    }

    @Override
    public int getIntrinsicWidth() {
      return 24;
    }

    @Override
    public int getIntrinsicHeight() {
      return 24;
    }

    @Override
    public boolean isStateful() {
      return tint != null && tint.isStateful();
    }

    @Override
    public void setTintList(ColorStateList value) {
      tint = value;
      onStateChange(getState());
    }

    @Override
    protected boolean onStateChange(int[] state) {
      if (tint == null) return false;
      paint.setColor(tint.getColorForState(state, tint.getDefaultColor()));
      invalidateSelf();
      return true;
    }

    @Override
    public void setAlpha(int alpha) {
      paint.setAlpha(alpha);
      invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
      paint.setColorFilter(filter);
      invalidateSelf();
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
