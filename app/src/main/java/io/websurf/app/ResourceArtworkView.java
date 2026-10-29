package io.websurf.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Supplied artwork, with loading animation active only while this view is on screen. */
final class ResourceArtworkView extends ImageView {
  private static final LruCache<String, Bitmap> STATIC_ART =
      new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
          return bitmap.getAllocationByteCount();
        }
      };

  enum Kind {
    UNKNOWN,
    DOCUMENT,
    AUDIO,
    LOADING
  }

  private Kind kind;
  private final ViewTreeObserver.OnPreDrawListener visibility =
      () -> {
        updateAnimation();
        return true;
      };

  ResourceArtworkView(Context context) {
    super(context);
    setScaleType(ScaleType.FIT_CENTER);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  Kind getKind() {
    return kind;
  }

  void show(Kind value) {
    if (kind == value && getDrawable() != null) {
      updateAnimation();
      return;
    }
    stopAnimation();
    kind = value;
    Drawable drawable;
    if (value == Kind.LOADING) {
      drawable = loading();
    } else {
      int id =
          value == Kind.AUDIO
              ? R.drawable.ws_resource_audio
              : value == Kind.DOCUMENT
                  ? R.drawable.ws_resource_document
                  : R.drawable.ws_resource_unknown;
      drawable = artwork(id);
    }
    setImageDrawable(drawable);
    updateAnimation();
  }

  private Drawable artwork(int id) {
    int target = Ui.dp(getContext(), 84);
    String key = id + "@" + target;
    Bitmap bitmap = STATIC_ART.get(key);
    if (bitmap != null) return new BitmapDrawable(getResources(), bitmap);
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeResource(getResources(), id, options);
    options.inJustDecodeBounds = false;
    options.inSampleSize = 1;
    while (Math.min(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= target)
      options.inSampleSize *= 2;
    bitmap = BitmapFactory.decodeResource(getResources(), id, options);
    if (bitmap != null) STATIC_ART.put(key, bitmap);
    return new BitmapDrawable(getResources(), bitmap);
  }

  private Drawable loading() {
    try (InputStream stream = getResources().openRawResource(R.raw.ws_resource_loading_animated)) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] block = new byte[8192];
      int count;
      while ((count = stream.read(block)) != -1) bytes.write(block, 0, count);
      byte[] encoded = bytes.toByteArray();
      if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeDrawable(
            ImageDecoder.createSource(ByteBuffer.wrap(encoded)),
            (decoder, info, source) -> {
              int size = Math.min(info.getSize().getWidth(), Ui.dp(getContext(), 84));
              decoder.setTargetSize(size, size);
            });
      }
      Movie movie = Movie.decodeByteArray(encoded, 0, encoded.length);
      if (movie != null) {
        // The platform Movie renderer needs a software canvas on Android 8.x.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        return new LegacyLoadingDrawable(movie);
      }
    } catch (IOException | RuntimeException ignored) {
      // Keep a useful loading state even if the platform GIF decoder is unavailable.
    }
    return artwork(R.drawable.ws_resource_loading_static);
  }

  boolean isAnimating() {
    return getDrawable() instanceof Animatable && ((Animatable) getDrawable()).isRunning();
  }

  private void stopAnimation() {
    if (getDrawable() instanceof Animatable) ((Animatable) getDrawable()).stop();
  }

  private void updateAnimation() {
    if (!(getDrawable() instanceof Animatable)) return;
    Animatable animation = (Animatable) getDrawable();
    boolean visible =
        isAttachedToWindow()
            && getWindowVisibility() == VISIBLE
            && isShown()
            && getGlobalVisibleRect(new Rect());
    if (kind == Kind.LOADING && visible && ValueAnimator.areAnimatorsEnabled()) {
      if (!animation.isRunning()) animation.start();
    } else animation.stop();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    getViewTreeObserver().addOnPreDrawListener(visibility);
    updateAnimation();
  }

  @Override
  protected void onDetachedFromWindow() {
    getViewTreeObserver().removeOnPreDrawListener(visibility);
    stopAnimation();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onVisibilityChanged(View changedView, int value) {
    super.onVisibilityChanged(changedView, value);
    updateAnimation();
  }

  @Override
  protected void onWindowVisibilityChanged(int value) {
    super.onWindowVisibilityChanged(value);
    updateAnimation();
  }

  @SuppressWarnings("deprecation")
  private static final class LegacyLoadingDrawable extends Drawable
      implements Animatable, Runnable {
    private final Movie movie;
    private boolean running;
    private long started;

    LegacyLoadingDrawable(Movie movie) {
      this.movie = movie;
    }

    @Override
    public int getIntrinsicWidth() {
      return movie.width();
    }

    @Override
    public int getIntrinsicHeight() {
      return movie.height();
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(ColorFilter filter) {}

    @Override
    public void draw(Canvas canvas) {
      int duration = movie.duration() > 0 ? movie.duration() : 1000;
      movie.setTime(running ? (int) ((SystemClock.uptimeMillis() - started) % duration) : 0);
      Rect bounds = getBounds();
      int save = canvas.save();
      canvas.translate(bounds.left, bounds.top);
      canvas.scale(
          (float) bounds.width() / movie.width(), (float) bounds.height() / movie.height());
      movie.draw(canvas, 0, 0);
      canvas.restoreToCount(save);
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    @Override
    public void start() {
      if (running) return;
      running = true;
      started = SystemClock.uptimeMillis();
      run();
    }

    @Override
    public void stop() {
      running = false;
      unscheduleSelf(this);
    }

    @Override
    public void run() {
      if (!running) return;
      invalidateSelf();
      scheduleSelf(this, SystemClock.uptimeMillis() + 40);
    }
  }
}
