package io.websurf.app;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resource identity is shown by its real poster/frame alongside explicit pixel dimensions. */
public final class ResourceRow extends LinearLayout {
  private final WebResource resource;
  private final ResourcePreviewLoader loader;
  private final Map<String, String> headers;
  private final ImageView image;
  private final TextView placeholder, imageSource, quality, variants, details;
  private boolean requested;
  private final ViewTreeObserver.OnPreDrawListener visibility = this::loadWhenVisible;

  public ResourceRow(
      Context context,
      WebResource resource,
      ResourcePreviewLoader loader,
      Map<String, String> headers,
      Runnable click) {
    super(context);
    this.resource = resource;
    this.loader = loader;
    this.headers = new LinkedHashMap<>(headers);
    setOrientation(HORIZONTAL);
    setGravity(Gravity.TOP);
    setPadding(dp(10), dp(10), dp(10), dp(10));
    setMinimumHeight(dp(108));
    Ui.ripple(this, Ui.SURFACE, 8);
    setOnClickListener(view -> click.run());
    LinearLayout preview = new LinearLayout(context);
    preview.setOrientation(VERTICAL);
    LayoutParams previewParams = new LayoutParams(dp(104), -2);
    previewParams.rightMargin = dp(12);
    addView(preview, previewParams);
    FrameLayout picture = new FrameLayout(context);
    picture.setBackground(Ui.shape(context, Ui.BG, 6));
    picture.setClipToOutline(true);
    preview.addView(picture, new LayoutParams(-1, dp(64)));
    image = new ImageView(context);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setContentDescription(resource.label() + "的缩略图");
    picture.addView(image, new FrameLayout.LayoutParams(-1, -1));
    placeholder = Ui.text(context, resource.isVideo() ? "预览待识别" : resource.kind, 12, Ui.MUTED);
    placeholder.setGravity(Gravity.CENTER);
    picture.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
    imageSource = Ui.text(context, "", 11, Ui.MUTED);
    imageSource.setGravity(Gravity.CENTER);
    imageSource.setMinHeight(dp(28));
    imageSource.setContentDescription("识别视频预览");
    preview.addView(imageSource, new LayoutParams(-1, -2));
    imageSource.setOnClickListener(view -> request(true));
    LinearLayout text = new LinearLayout(context);
    text.setOrientation(VERTICAL);
    addView(text, new LayoutParams(0, -2, 1));
    TextView title = Ui.text(context, resource.label(), 14, Ui.TEXT);
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    text.addView(title, new LayoutParams(-1, -2));
    quality = Ui.text(context, "", 14, Ui.CONTROL);
    quality.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    quality.setPadding(0, dp(5), 0, dp(3));
    quality.setMaxLines(2);
    text.addView(quality, new LayoutParams(-1, -2));
    variants = Ui.text(context, "", 12, Ui.MUTED);
    variants.setMaxLines(2);
    text.addView(variants, new LayoutParams(-1, -2));
    details = Ui.text(context, "", 12, Ui.MUTED);
    details.setPadding(0, dp(2), 0, dp(4));
    text.addView(details, new LayoutParams(-1, -2));
    TextView url = Ui.text(context, resource.url, 11, Ui.MUTED);
    url.setSingleLine(true);
    url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    text.addView(url, new LayoutParams(-1, -2));
    updateText();
  }

  private int dp(int value) {
    return Ui.dp(getContext(), value);
  }

  private void updateText() {
    quality.setText(resource.qualityLabel());
    quality.setVisibility(resource.isVideo() ? VISIBLE : GONE);
    variants.setText(
        resource.availableQualities.isEmpty() ? "" : "可选 " + resource.availableQualities);
    variants.setVisibility(resource.availableQualities.isEmpty() ? GONE : VISIBLE);
    String duration =
        resource.durationMs > 0
            ? " · "
                + String.format(
                    java.util.Locale.ROOT,
                    "%d:%02d",
                    resource.durationMs / 60000,
                    (resource.durationMs / 1000) % 60)
            : "";
    details.setText(resource.formatLabel() + duration);
    setContentDescription(
        resource.label() + "，" + resource.qualityLabel() + "，" + resource.formatLabel());
  }

  private boolean loadWhenVisible() {
    if (!requested && isAttachedToWindow() && getGlobalVisibleRect(new Rect())) request(false);
    return true;
  }

  private void request(boolean retry) {
    if (!resource.isVideo() && !"图片".equals(resource.kind)) {
      requested = true;
      imageSource.setText("");
      return;
    }
    requested = true;
    getViewTreeObserver().removeOnPreDrawListener(visibility);
    imageSource.setText("识别中…");
    imageSource.setEnabled(false);
    WeakReference<ResourceRow> reference = new WeakReference<>(this);
    ResourcePreviewLoader.Callback callback =
        result -> {
          ResourceRow row = reference.get();
          if (row == null || !row.isAttachedToWindow()) return;
          row.updateText();
          row.imageSource.setEnabled(true);
          if (result.bitmap != null) {
            row.image.setImageBitmap(result.bitmap);
            row.placeholder.setVisibility(GONE);
            row.imageSource.setText(result.imageSource);
            row.imageSource.setContentDescription("重新识别视频预览");
          } else {
            row.placeholder.setVisibility(VISIBLE);
            row.placeholder.setText("暂无预览");
            row.imageSource.setText("重试预览");
            row.imageSource.setContentDescription("重试识别视频预览");
          }
        };
    if (retry) loader.retry(resource, headers, callback);
    else loader.load(resource, headers, callback);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (!requested) getViewTreeObserver().addOnPreDrawListener(visibility);
  }

  @Override
  protected void onDetachedFromWindow() {
    getViewTreeObserver().removeOnPreDrawListener(visibility);
    requested = false;
    super.onDetachedFromWindow();
  }
}
