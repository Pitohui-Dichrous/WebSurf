package io.websurf.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
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

  private final TextView title, status, progressValue, transferRate, metrics, error;
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
    setPadding(dp(14), dp(14), dp(14), dp(12));
    setBackground(Ui.outline(context, Ui.SURFACE, Ui.BORDER, 0));
    title = Ui.text(context, "", 15, Ui.TEXT);
    title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    addView(title, new LayoutParams(-1, -2));

    LinearLayout signal = new LinearLayout(context);
    signal.setGravity(Gravity.CENTER_VERTICAL);
    signal.setPadding(0, dp(10), 0, dp(7));
    addView(signal, new LayoutParams(-1, -2));
    progressValue = Ui.display(context, "", 42, Ui.CONTROL);
    progressValue.setSingleLine(true);
    signal.addView(progressValue, new LayoutParams(-2, -2));
    LinearLayout stateInfo = new LinearLayout(context);
    stateInfo.setOrientation(VERTICAL);
    stateInfo.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    LayoutParams stateParams = new LayoutParams(0, -2, 1);
    stateParams.leftMargin = dp(12);
    signal.addView(stateInfo, stateParams);
    status = Ui.text(context, "", 13, Ui.TEXT);
    status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    status.setGravity(Gravity.RIGHT);
    stateInfo.addView(status, new LayoutParams(-1, -2));
    transferRate = Ui.mono(context, "", 12, Ui.MUTED);
    transferRate.setGravity(Gravity.RIGHT);
    transferRate.setPadding(0, dp(6), 0, 0);
    stateInfo.addView(transferRate, new LayoutParams(-1, -2));

    progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setProgressTintList(ColorStateList.valueOf(Ui.ACTION));
    progress.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACTION));
    progress.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.BORDER));
    LayoutParams bar = new LayoutParams(-1, dp(6));
    bar.bottomMargin = dp(10);
    addView(progress, bar);
    metrics = Ui.mono(context, "", 11, Ui.MUTED);
    metrics.setMaxLines(3);
    addView(metrics, new LayoutParams(-1, -2));
    error = Ui.text(context, "", 12, Ui.isDark(context) ? 0xffffb4ab : Ui.RED);
    error.setTextIsSelectable(true);
    error.setPadding(0, dp(10), 0, dp(2));
    addView(error, new LayoutParams(-1, -2));
    actions = new LinearLayout(context);
    actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    actions.setPadding(0, dp(12), 0, 0);
    addView(actions, new LayoutParams(-1, -2));
  }

  void bind(DownloadStore.Job value) {
    job = value;
    title.setText(value.title);
    title.setContentDescription(value.title);
    title.setTooltipText(value.title);
    boolean completed = "completed".equals(value.state);
    boolean measured = completed || value.total > 0 || value.totalParts > 0 || value.progress > 0;
    progressValue.setText(completed ? "100%" : measured ? value.progress + "%" : "—");
    progressValue.setContentDescription(
        measured ? "下载进度 " + (completed ? 100 : value.progress) + "%" : "下载总量未知");
    status.setText(value.stateLabel());
    status.setTextColor(
        "failed".equals(value.state) ? (Ui.isDark(getContext()) ? 0xffffb4ab : Ui.RED) : Ui.TEXT);
    progress.setVisibility(value.active() ? VISIBLE : GONE);
    progress.setIndeterminate(
        "queued".equals(value.state)
            || ("downloading".equals(value.state) && value.total < 0 && value.totalParts <= 0));
    progress.setProgress(value.progress);
    String detail = bytes(value.bytes) + (value.total > 0 ? " / " + bytes(value.total) : "");
    if (value.totalParts > 0)
      detail += "\n" + value.completedParts + " / " + value.totalParts + " 分片";
    String rate = "";
    if ("downloading".equals(value.state)) {
      if (value.bytesPerSecond > 0) rate = bytes(value.bytesPerSecond) + "/s";
      if (value.connections > 0) rate += (rate.isEmpty() ? "" : "\n") + value.connections + " 路";
    }
    transferRate.setText(rate);
    transferRate.setVisibility(rate.isEmpty() ? GONE : VISIBLE);
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
    Button button = Ui.button(getContext(), label, actions.getChildCount() == 0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(10), dp(8), dp(10), dp(8));
    button.setMaxLines(1);
    LayoutParams params = new LayoutParams(0, dp(48), 1);
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
