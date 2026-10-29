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
import androidx.core.graphics.PathParser;

/** Acid edition: neutral planes, a single fluorescent accent, and deliberate type contrast. */
public final class Ui {
  public static int BG = 0xff000000;
  public static int SURFACE = 0xff171717;
  public static int BORDER = 0xff454545;
  public static int TEXT = 0xfff5f5f5;
  public static int MUTED = 0xffb6b6b6;
  public static int SOFT_ACCENT = 0xff242424;
  public static int CONTROL = 0xffc0fe03;
  public static final int ACCENT = 0xffc0fe03;
  public static int ACTION = ACCENT;
  public static int ACTION_INK = Color.BLACK;
  public static int PLANE = Color.BLACK;
  public static int PLANE_INK = ACCENT;
  public static final int RED = 0xffb3261e;
  private static boolean dark;
  private static Typeface displayFace;
  private static final PathInterpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);

  private Ui() {}

  public static boolean isDark(Context context) {
    return context
        .getSharedPreferences("appearance", Context.MODE_PRIVATE)
        .getBoolean("dark", true);
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
    BG = dark ? Color.BLACK : 0xfff2f2f2;
    SURFACE = dark ? 0xff171717 : Color.WHITE;
    BORDER = dark ? 0xff454545 : 0xffbcbcbc;
    TEXT = dark ? 0xfff5f5f5 : Color.BLACK;
    MUTED = dark ? 0xffb6b6b6 : 0xff555555;
    SOFT_ACCENT = dark ? 0xff242424 : 0xffe4e4e4;
    CONTROL = dark ? ACCENT : Color.BLACK;
    ACTION = dark ? ACCENT : Color.BLACK;
    ACTION_INK = dark ? Color.BLACK : ACCENT;
    PLANE = dark ? Color.BLACK : ACCENT;
    PLANE_INK = dark ? ACCENT : Color.BLACK;
  }

  public static int dp(Context context, float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  public static TextView text(Context context, String value, float size, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    view.setIncludeFontPadding(false);
    view.setGravity(Gravity.CENTER_VERTICAL);
    return view;
  }

  public static TextView display(Context context, String value, float size, int color) {
    TextView view = text(context, value, size, color);
    if (displayFace == null)
      displayFace = context.getResources().getFont(R.font.barlow_condensed_extrabold);
    view.setTypeface(displayFace);
    view.setLetterSpacing(-.025f);
    return view;
  }

  public static TextView mono(Context context, String value, float size, int color) {
    TextView view = text(context, value, size, color);
    view.setTypeface(Typeface.MONOSPACE);
    view.setFontFeatureSettings("tnum");
    return view;
  }

  public static View rule(Context context, int color) {
    View view = new View(context);
    view.setBackgroundColor(color);
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    view.setMinimumHeight(dp(context, 1));
    return view;
  }

  public static GradientDrawable outline(Context context, int fill, int stroke, int radius) {
    GradientDrawable drawable = shape(context, fill, radius);
    drawable.setStroke(dp(context, 1), stroke);
    return drawable;
  }

  public static GradientDrawable shape(Context context, int color, int radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(context, Math.min(2, radius)));
    return drawable;
  }

  public static void ripple(View view, int color, int radius) {
    Context context = view.getContext();
    view.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(dark ? 0x30ffffff : 0x20000000),
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
    button.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
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
    int foreground = primary ? ACTION_INK : TEXT;
    button.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {(foreground & 0x00ffffff) | 0x77000000, foreground}));
    button.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(primary ? 0x30000000 : (dark ? 0x30ffffff : 0x20000000)),
            outline(context, primary ? ACTION : SURFACE, primary ? ACTION : BORDER, 2),
            shape(context, Color.WHITE, 2)));
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

  /** Official Lucide paths; aliases retain the existing controls API. */
  public static final class Icon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] paths;
    private final int originalColor;
    private ColorStateList tint;
    private int alpha = 255;

    public Icon(String kind, int color) {
      originalColor = color;
      String[] data = ImportedIcons.paths(kind);
      paths = new Path[data.length];
      for (int i = 0; i < data.length; i++) paths[i] = PathParser.createPathFromPathData(data[i]);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(2f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      updateColor();
    }

    @Override
    public void draw(Canvas canvas) {
      Rect bounds = getBounds();
      canvas.save();
      float size = Math.min(bounds.width(), bounds.height());
      canvas.translate(
          bounds.left + (bounds.width() - size) / 2f, bounds.top + (bounds.height() - size) / 2f);
      canvas.scale(size / 24f, size / 24f);
      for (Path path : paths) canvas.drawPath(path, paint);
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
      updateColor();
    }

    @Override
    protected boolean onStateChange(int[] state) {
      if (tint == null) return false;
      updateColor();
      return true;
    }

    private void updateColor() {
      int color =
          tint == null ? originalColor : tint.getColorForState(getState(), tint.getDefaultColor());
      paint.setColor(color);
      paint.setAlpha(Math.round(Color.alpha(color) * alpha / 255f));
      invalidateSelf();
    }

    @Override
    public void setAlpha(int value) {
      alpha = value;
      updateColor();
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
