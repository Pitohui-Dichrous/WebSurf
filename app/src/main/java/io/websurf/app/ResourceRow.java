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
  private final TextView placeholder, imageSource, quality, dimensions, variants, details;
  private final FrameLayout picture;
  private final Typeface qualityTypeface;
  private final float qualitySize;
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
    setOrientation(VERTICAL);
    setGravity(Gravity.TOP);
    setPadding(dp(12), dp(14), dp(12), 0);
    Ui.ripple(this, Ui.SURFACE, 0);
    setOnClickListener(view -> click.run());

    LinearLayout media = new LinearLayout(context);
    media.setGravity(Gravity.TOP);
    addView(media, new LayoutParams(-1, -2));
    LinearLayout preview = new LinearLayout(context);
    preview.setOrientation(VERTICAL);
    boolean compact =
        getResources().getConfiguration().screenWidthDp < 360
            || getResources().getConfiguration().fontScale > 1.3f;
    int previewWidth = compact ? 100 : 120;
    LayoutParams previewParams = new LayoutParams(dp(previewWidth), -2);
    previewParams.rightMargin = dp(14);
    media.addView(preview, previewParams);
    picture = new FrameLayout(context);
    picture.setBackground(Ui.outline(context, Ui.BG, Ui.BORDER, 0));
    picture.setClipToOutline(true);
    preview.addView(picture, new LayoutParams(-1, dp(compact ? 75 : 84)));
    image = new ImageView(context);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setContentDescription(resource.label() + "的缩略图");
    picture.addView(image, new FrameLayout.LayoutParams(-1, -1));
    placeholder = Ui.text(context, resource.isVideo() ? "预览" : resource.kind, 12, Ui.MUTED);
    placeholder.setGravity(Gravity.CENTER);
    picture.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
    imageSource = Ui.text(context, "", 11, Ui.MUTED);
    imageSource.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
    imageSource.setMinHeight(dp(22));
    imageSource.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    preview.addView(imageSource, new LayoutParams(-1, -2));

    LinearLayout facts = new LinearLayout(context);
    facts.setOrientation(VERTICAL);
    media.addView(facts, new LayoutParams(0, -2, 1));
    quality = Ui.display(context, "", compact ? 29 : 34, Ui.CONTROL);
    qualityTypeface = quality.getTypeface();
    qualitySize = quality.getTextSize();
    quality.setMaxLines(2);
    facts.addView(quality, new LayoutParams(-1, -2));
    dimensions = Ui.mono(context, "", 11, Ui.MUTED);
    dimensions.setMaxLines(2);
    dimensions.setPadding(0, dp(3), 0, dp(5));
    facts.addView(dimensions, new LayoutParams(-1, -2));
    details = Ui.text(context, "", 12, Ui.TEXT);
    details.setMaxLines(2);
    details.setPadding(0, dp(3), 0, dp(3));
    facts.addView(details, new LayoutParams(-1, -2));

    TextView title = Ui.text(context, resource.label(), 15, Ui.TEXT);
    title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setContentDescription(resource.label());
    title.setTooltipText(resource.label());
    title.setPadding(0, dp(7), 0, dp(5));
    addView(title, new LayoutParams(-1, -2));
    variants = Ui.text(context, "", 11, Ui.MUTED);
    variants.setMaxLines(2);
    variants.setEllipsize(TextUtils.TruncateAt.END);
    addView(variants, new LayoutParams(-1, -2));
    TextView url = Ui.mono(context, resource.url, 10, Ui.MUTED);
    url.setSingleLine(true);
    url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    url.setPadding(0, dp(5), 0, dp(12));
    addView(url, new LayoutParams(-1, -2));
    addView(Ui.rule(context, Ui.BORDER), new LayoutParams(-1, dp(1)));
    updateText();
  }

  private int dp(int value) {
    return Ui.dp(getContext(), value);
  }

  private void updateText() {
    boolean known = resource.isVideo() && resource.width > 0 && resource.height > 0;
    if (known) {
      quality.setTypeface(qualityTypeface);
      quality.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, qualitySize);
      quality.setText(Math.min(resource.width, resource.height) + "p");
      int maximum = Math.min(resource.maxVariantWidth, resource.maxVariantHeight);
      dimensions.setText(
          resource.width
              + " × "
              + resource.height
              + (maximum > Math.min(resource.width, resource.height)
                  ? "\n最高 " + maximum + "p"
                  : ""));
    } else {
      quality.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      quality.setTextSize(15);
      if (resource.isVideo()) {
        int maximum = Math.min(resource.maxVariantWidth, resource.maxVariantHeight);
        quality.setText(maximum > 0 ? "自适应" : "清晰度待识别");
        dimensions.setText(
            maximum > 0
                ? "最高 " + maximum + "p"
                : resource.width > 0
                    ? "宽 " + resource.width + " px"
                    : resource.height > 0 ? "高 " + resource.height + " px" : "");
      } else {
        quality.setText(resource.kind);
        dimensions.setText("");
      }
    }
    dimensions.setVisibility(dimensions.getText().length() > 0 ? VISIBLE : GONE);
    quality.setContentDescription(resource.qualityLabel());
    variants.setText(resource.availableQualities.isEmpty() ? "" : resource.availableQualities);
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
    imageSource.setText("加载中");
    picture.setOnClickListener(null);
    picture.setClickable(false);
    picture.setFocusable(false);
    picture.setContentDescription(null);
    WeakReference<ResourceRow> reference = new WeakReference<>(this);
    ResourcePreviewLoader.Callback callback =
        result -> {
          ResourceRow row = reference.get();
          if (row == null || !row.isAttachedToWindow()) return;
          row.updateText();
          if (result.bitmap != null) {
            row.image.setImageBitmap(result.bitmap);
            row.placeholder.setVisibility(GONE);
            row.imageSource.setText(result.imageSource);
            row.picture.setContentDescription(result.imageSource);
          } else {
            row.placeholder.setVisibility(VISIBLE);
            row.placeholder.setText("暂无预览");
            row.imageSource.setText("重试预览");
            row.picture.setContentDescription("重试预览");
            row.picture.setOnClickListener(view -> row.request(true));
            Ui.ripple(row.picture, Ui.BG, 0);
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
