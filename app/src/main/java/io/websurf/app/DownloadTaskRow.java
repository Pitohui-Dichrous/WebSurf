package io.websurf.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import io.websurf.app.download.DownloadStore;
import java.util.Locale;

/** Stable task views: byte updates never replace focused actions or reset the scroll position. */
final class DownloadTaskRow extends LinearLayout {
  interface Actions {
    void open(DownloadStore.Job job);

    void preview(DownloadStore.Job job);

    void retry(DownloadStore.Job job);

    void cancel(DownloadStore.Job job);

    void remove(DownloadStore.Job job);
  }

  private final TextView title, status, metrics, error;
  private final ProgressBar progress;
  private final LinearLayout actions;
  private final Actions callback;
  private DownloadStore.Job job;
  private String state = "";
  private boolean canPreview;

  DownloadTaskRow(Context context, Actions callback) {
    super(context);
    this.callback = callback;
    setOrientation(VERTICAL);
    setPadding(dp(12), dp(12), dp(12), dp(8));
    setBackground(Ui.shape(context, Ui.SURFACE, 6));
    title = Ui.text(context, "", 14, Ui.TEXT);
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    addView(title, new LayoutParams(-1, -2));
    status = Ui.text(context, "", 12, Ui.CONTROL);
    status.setPadding(0, dp(8), 0, dp(4));
    addView(status);
    progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
    LayoutParams bar = new LayoutParams(-1, dp(4));
    bar.topMargin = dp(4);
    bar.bottomMargin = dp(8);
    addView(progress, bar);
    metrics = Ui.text(context, "", 12, Ui.MUTED);
    metrics.setMaxLines(2);
    addView(metrics);
    error = Ui.text(context, "", 12, Ui.isDark(context) ? 0xffffb4ab : Ui.RED);
    error.setTextIsSelectable(true);
    error.setPadding(0, dp(8), 0, dp(4));
    addView(error);
    actions = new LinearLayout(context);
    actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    addView(actions, new LayoutParams(-1, -2));
  }

  void bind(DownloadStore.Job value) {
    job = value;
    title.setText(value.title);
    title.setContentDescription(value.title);
    title.setTooltipText(value.title);
    String percent =
        value.active() && !"queued".equals(value.state) && value.progress > 0
            ? " · " + value.progress + "%"
            : "";
    status.setText(value.stateLabel() + percent);
    progress.setVisibility(value.active() ? VISIBLE : GONE);
    progress.setIndeterminate(
        "queued".equals(value.state)
            || ("downloading".equals(value.state) && value.total < 0 && value.totalParts <= 0));
    progress.setProgress(value.progress);
    String detail = bytes(value.bytes) + (value.total > 0 ? " / " + bytes(value.total) : "");
    if (value.totalParts > 0)
      detail += " · " + value.completedParts + " / " + value.totalParts + " 分片";
    if ("downloading".equals(value.state)) {
      if (value.bytesPerSecond > 0) detail += "\n" + bytes(value.bytesPerSecond) + "/s";
      if (value.connections > 0) detail += " · " + value.connections + " 路";
    }
    metrics.setText(detail);
    error.setText(value.error);
    error.setVisibility(value.error == null || value.error.isBlank() ? GONE : VISIBLE);
    boolean previewable =
        value.mimeType.startsWith("video/")
            || value.mimeType.contains("mpegurl")
            || WebResource.classify(value.url, value.mimeType).equals("视频");
    if (!state.equals(value.state) || canPreview != previewable) {
      canPreview = previewable;
      state = value.state;
      actions.removeAllViews();
      if ("completed".equals(state)) addAction("打开", () -> callback.open(job));
      else if ("failed".equals(state) || "cancelled".equals(state))
        addAction("重试", () -> callback.retry(job));
      else {
        if (previewable) addAction("边下边播", () -> callback.preview(job));
        addAction("取消", () -> callback.cancel(job));
      }
      if (!value.active()) addAction("移除记录", () -> callback.remove(job));
    }
  }

  private void addAction(String label, Runnable click) {
    Button button = Ui.button(getContext(), label, false);
    LayoutParams params = new LayoutParams(-2, dp(48));
    params.leftMargin = actions.getChildCount() == 0 ? 0 : dp(8);
    actions.addView(button, params);
    button.setOnClickListener(v -> click.run());
  }

  private int dp(int value) {
    return Ui.dp(getContext(), value);
  }

  static String bytes(long value) {
    if (value < 1024) return value + " B";
    if (value < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", value / 1024.0);
    if (value < 1024L * 1024 * 1024)
      return String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024));
    return String.format(Locale.ROOT, "%.2f GB", value / (1024.0 * 1024 * 1024));
  }
}
