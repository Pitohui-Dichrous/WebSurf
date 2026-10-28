package io.websurf.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.util.Locale;

/**
 * Native controls shared by page-positioned, fullscreen and user-opened floating playback. The
 * owner positions this view and owns the player; changing mode never replaces the decoder.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class VideoChrome extends FrameLayout {
  public enum Mode {
    INLINE,
    FULLSCREEN,
    FLOATING
  }

  public interface Callback {
    void onClose();

    void onModeRequested(Mode mode);

    void onDownload();

    void onSelectSource();

    default void onSelectQuality() {}

    void onDrag(float dx, float dy);

    void onRetry();
  }

  private static final long HIDE_DELAY_MS = 3000;
  private static final int SEEK_STEPS = 10000;
  private static final int WHITE = Color.WHITE;
  private static final float[] SPEEDS = {.5f, 1f, 1.25f, 1.5f, 2f, 3f};
  private static final String[] SPEED_LABELS = {
    "0.5 倍", "1.0 倍", "1.25 倍", "1.5 倍", "2.0 倍", "3.0 倍"
  };
  private static final PathInterpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);
  private final Callback callback;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final PlayerView playerView;
  private final View touchSurface;
  private final FrameLayout controls;
  private final LinearLayout topBar;
  private final LinearLayout bottomBar;
  private final TextView titleView;
  private final TextView currentTime;
  private final TextView durationTime;
  private final TextView speedButton;
  private final TextView qualityButton;
  private final TextView gestureHud;
  private final TextView errorLabel;
  private final LinearLayout errorPanel;
  private final ProgressBar buffering;
  private final SeekBar seekBar;
  private final ImageButton playButton;
  private final ImageButton muteButton;
  private final ImageButton fullscreenButton;
  private final ImageButton floatingButton;
  private final ImageButton downloadButton;
  private final ImageButton moreButton;
  private final ImageButton lockButton;
  private final GestureDetector tapDetector;
  private final AudioManager audio;
  private final AccessibilityManager accessibility;
  private final Activity activity;
  private final int touchSlop;
  private ExoPlayer player;
  private Mode mode = Mode.INLINE;
  private boolean locked;
  private boolean controlsVisible = true;
  private boolean scrubbing;
  private boolean touching;
  private boolean boosting;
  private boolean released;
  private boolean downloadVisible = true;
  private boolean changedBrightness;
  private boolean menuOpen;
  private float originalBrightness;
  private float volumeBeforeMute = 1f;
  private float speedBeforeBoost = 1f;
  private float touchX, touchY, dragX, dragY;
  private float startBrightness;
  private int startVolume;
  private int gesture;
  private long startPosition, seekTarget;
  private AlertDialog speedDialog;
  private PopupMenu popup;

  private final Runnable hideControls =
      () -> {
        if (canAutoHide()) setControlsVisible(false);
      };
  private final Runnable hideHud = this::hideGestureHud;
  private final Runnable progressTick =
      new Runnable() {
        @Override
        public void run() {
          if (released || !isAttachedToWindow()) return;
          updateProgress();
          handler.postDelayed(this, 300);
        }
      };
  private final Player.Listener playerListener =
      new Player.Listener() {
        @Override
        public void onEvents(Player value, Player.Events events) {
          if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) cancelGestures();
          updateState();
        }
      };

  public VideoChrome(Context context, Callback callback) {
    super(context);
    this.callback = callback;
    activity = findActivity(context);
    audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    accessibility = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    setBackgroundColor(Color.BLACK);
    setClipChildren(true);
    setClipToPadding(true);
    setMinimumHeight(dp(96));

    playerView =
        (PlayerView) LayoutInflater.from(context).inflate(R.layout.native_video, this, false);
    playerView.setUseController(false);
    playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
    playerView.setShutterBackgroundColor(Color.BLACK);
    addView(playerView, new LayoutParams(-1, -1));

    touchSurface = new View(context);
    touchSurface.setContentDescription("视频画面，点按显示控制，双击播放或暂停");
    touchSurface.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(touchSurface, new LayoutParams(-1, -1));

    controls = new FrameLayout(context);
    addView(controls, new LayoutParams(-1, -1));
    topBar = new LinearLayout(context);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(4), 0, dp(2), 0);
    topBar.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xb8000000, 0x00000000}));
    controls.addView(topBar, new LayoutParams(-1, dp(48), Gravity.TOP));
    titleView = label("视频", 13);
    titleView.setSingleLine(true);
    titleView.setEllipsize(TextUtils.TruncateAt.END);
    titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    titleView.setPadding(dp(8), 0, dp(4), 0);
    topBar.addView(titleView, new LinearLayout.LayoutParams(0, -1, 1));
    downloadButton = icon("download", "下载视频", () -> callback.onDownload());
    topBar.addView(downloadButton, buttonParams());
    moreButton = icon("more", "更多视频操作", this::showMore);
    topBar.addView(moreButton, buttonParams());
    topBar.addView(icon("close", "关闭接管，返回网页播放", () -> callback.onClose()), buttonParams());

    bottomBar = new LinearLayout(context);
    bottomBar.setGravity(Gravity.CENTER_VERTICAL);
    bottomBar.setPadding(dp(2), 0, dp(2), 0);
    bottomBar.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0xbd000000}));
    controls.addView(bottomBar, new LayoutParams(-1, dp(48), Gravity.BOTTOM));
    playButton = icon("play", "播放", this::togglePlayback);
    bottomBar.addView(playButton, buttonParams());
    currentTime = label("00:00", 12);
    currentTime.setGravity(Gravity.CENTER);
    bottomBar.addView(currentTime, new LinearLayout.LayoutParams(dp(45), -1));
    seekBar = new SeekBar(context);
    seekBar.setMax(SEEK_STEPS);
    seekBar.setContentDescription("播放进度");
    seekBar.setPadding(dp(8), 0, dp(8), 0);
    seekBar.setProgressTintList(ColorStateList.valueOf(WHITE));
    seekBar.setSecondaryProgressTintList(ColorStateList.valueOf(0x80ffffff));
    seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x45ffffff));
    seekBar.setThumbTintList(ColorStateList.valueOf(WHITE));
    bottomBar.addView(seekBar, new LinearLayout.LayoutParams(0, dp(48), 1));
    durationTime = label("--:--", 12);
    durationTime.setGravity(Gravity.CENTER);
    bottomBar.addView(durationTime, new LinearLayout.LayoutParams(dp(45), -1));
    speedButton = textButton("1.0×", "播放速度", this::showSpeeds);
    bottomBar.addView(speedButton, buttonParams());
    qualityButton = textButton("画质", "选择播放画质", this::requestQuality);
    bottomBar.addView(qualityButton, buttonParams());
    muteButton = icon("volume", "静音", this::toggleMute);
    bottomBar.addView(muteButton, buttonParams());
    floatingButton =
        icon(
            "float",
            "小窗播放",
            () -> requestMode(mode == Mode.FLOATING ? Mode.INLINE : Mode.FLOATING));
    bottomBar.addView(floatingButton, buttonParams());
    fullscreenButton =
        icon(
            "expand",
            "全屏播放",
            () -> requestMode(mode == Mode.FULLSCREEN ? Mode.INLINE : Mode.FULLSCREEN));
    bottomBar.addView(fullscreenButton, buttonParams());

    buffering = new ProgressBar(context);
    buffering.setIndeterminateTintList(ColorStateList.valueOf(WHITE));
    buffering.setContentDescription("正在缓冲");
    addView(buffering, new LayoutParams(dp(32), dp(32), Gravity.CENTER));
    buffering.setVisibility(GONE);

    gestureHud = label("", 15);
    gestureHud.setGravity(Gravity.CENTER);
    gestureHud.setPadding(dp(16), dp(10), dp(16), dp(10));
    gestureHud.setBackground(Ui.shape(context, 0xcf18191b, 8));
    gestureHud.setVisibility(GONE);
    gestureHud.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    LayoutParams hudParams = new LayoutParams(-2, -2, Gravity.CENTER);
    hudParams.leftMargin = hudParams.rightMargin = dp(12);
    addView(gestureHud, hudParams);

    errorPanel = new LinearLayout(context);
    errorPanel.setGravity(Gravity.CENTER);
    errorPanel.setPadding(dp(8), 0, dp(8), 0);
    errorPanel.setBackground(Ui.shape(context, 0xdf18191b, 8));
    errorLabel = label("播放失败", 13);
    errorLabel.setMaxLines(2);
    errorLabel.setEllipsize(TextUtils.TruncateAt.END);
    errorPanel.addView(errorLabel, new LinearLayout.LayoutParams(-2, -2));
    TextView retry = textButton("重试", "重新播放视频", () -> callback.onRetry());
    errorPanel.addView(retry, new LinearLayout.LayoutParams(dp(64), dp(48)));
    LayoutParams errorParams = new LayoutParams(-2, dp(48), Gravity.CENTER);
    errorParams.leftMargin = errorParams.rightMargin = dp(12);
    addView(errorPanel, errorParams);
    errorPanel.setVisibility(GONE);

    lockButton = icon("unlock", "锁定播放控制", () -> setLocked(!locked));
    LayoutParams lockParams =
        new LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL | Gravity.LEFT);
    lockParams.leftMargin = dp(12);
    addView(lockButton, lockParams);
    lockButton.setVisibility(GONE);

    tapDetector =
        new GestureDetector(
            context,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }

              @Override
              public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!locked && gesture == 0 && !boosting) {
                  setControlsVisible(!controlsVisible || !isPlayingIntent());
                  scheduleHide();
                }
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                if (!locked) togglePlayback();
                return true;
              }

              @Override
              public void onLongPress(MotionEvent e) {
                if (touching
                    && !locked
                    && gesture == 0
                    && player != null
                    && isPlayingIntent()
                    && mode != Mode.FLOATING
                    && !player.isCurrentMediaItemLive()) {
                  boosting = true;
                  speedBeforeBoost = player.getPlaybackParameters().speed;
                  player.setPlaybackSpeed(2f);
                  showHud("2.0× 快进中");
                }
              }
            });
    touchSurface.setOnTouchListener(this::onVideoTouch);
    titleView.setOnTouchListener(this::onTitleTouch);
    seekBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onStartTrackingTouch(SeekBar bar) {
            scrubbing = true;
            handler.removeCallbacks(hideControls);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
          }

          @Override
          public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            if (fromUser && duration() > 0) {
              seekTarget = Math.round(duration() * (value / (double) SEEK_STEPS));
              currentTime.setText(formatTime(seekTarget));
              showHud(formatTime(seekTarget) + " / " + formatTime(duration()));
            }
          }

          @Override
          public void onStopTrackingTouch(SeekBar bar) {
            if (player != null && player.isCurrentMediaItemSeekable() && duration() > 0) {
              player.seekTo(Math.round(duration() * (bar.getProgress() / (double) SEEK_STEPS)));
            }
            scrubbing = false;
            gestureHud.setVisibility(GONE);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            updateProgress();
            scheduleHide();
          }
        });
    updateResponsiveControls(0);
    updateState();
  }

  public void bind(ExoPlayer value) {
    endBoost();
    if (player != null) player.removeListener(playerListener);
    player = value;
    released = false;
    playerView.setPlayer(value);
    if (player != null) player.addListener(playerListener);
    updateState();
    restartProgressUpdates();
  }

  public void setTitle(String title) {
    titleView.setText(TextUtils.isEmpty(title) ? "视频" : title);
  }

  public void setMode(Mode value) {
    if (value == null || value == mode) return;
    cancelGestures();
    if (mode == Mode.FULLSCREEN && value != Mode.FULLSCREEN) restoreBrightness();
    mode = value;
    if (mode != Mode.FULLSCREEN) locked = false;
    setButton(
        fullscreenButton,
        mode == Mode.FULLSCREEN ? "minimize" : "expand",
        mode == Mode.FULLSCREEN ? "返回网页位置播放" : "全屏播放");
    setButton(
        floatingButton,
        mode == Mode.FLOATING ? "inline" : "float",
        mode == Mode.FLOATING ? "返回网页位置播放" : "小窗播放");
    titleView.setContentDescription(mode == Mode.FLOATING ? "拖动标题移动小窗" : null);
    updateResponsiveControls(getWidth());
    showControls();
  }

  public void setDownloadVisible(boolean value) {
    downloadVisible = value;
    updateResponsiveControls(getWidth());
  }

  /** Preserve a page's logical gain when its current native output is muted. */
  public void setUnmutedVolume(float volume) {
    if (Float.isFinite(volume) && volume > 0) volumeBeforeMute = Math.min(1f, volume);
  }

  public boolean isLocked() {
    return locked;
  }

  public void setLocked(boolean value) {
    cancelGestures();
    locked = value && mode == Mode.FULLSCREEN;
    setButton(lockButton, locked ? "lock" : "unlock", locked ? "解锁播放控制" : "锁定播放控制");
    errorPanel.setVisibility(
        !locked && player != null && player.getPlayerError() != null ? VISIBLE : GONE);
    setControlsVisible(!locked);
    lockButton.setVisibility(mode == Mode.FULLSCREEN ? VISIBLE : GONE);
    if (locked) announceForAccessibility("播放控制已锁定");
    else scheduleHide();
  }

  public PlayerView getPlayerView() {
    return playerView;
  }

  /** The inline host may hand an untouched picture gesture to the WebView. */
  boolean isPageScrollStartAllowed(float rawX, float rawY) {
    if (mode != Mode.INLINE || released || locked || menuOpen) return false;
    return containsScreenPoint(touchSurface, rawX, rawY)
        && !containsScreenPoint(topBar, rawX, rawY)
        && !containsScreenPoint(bottomBar, rawX, rawY)
        && !containsScreenPoint(errorPanel, rawX, rawY)
        && !containsScreenPoint(lockButton, rawX, rawY);
  }

  boolean hasActivePlaybackGesture() {
    return boosting || scrubbing || gesture != 0 || locked || mode != Mode.INLINE || released;
  }

  private static boolean containsScreenPoint(View view, float rawX, float rawY) {
    if (!view.isShown() || view.getAlpha() <= 0) return false;
    int[] screen = new int[2];
    view.getLocationOnScreen(screen);
    return rawX >= screen[0]
        && rawX < screen[0] + view.getWidth()
        && rawY >= screen[1]
        && rawY < screen[1] + view.getHeight();
  }

  /** Useful after the owner restores a previously closed native session. */
  public void showControls() {
    setControlsVisible(!locked);
    scheduleHide();
  }

  /** Detaches this UI only. The owner alone may release the shared ExoPlayer. */
  public void release() {
    cancelGestures();
    released = true;
    handler.removeCallbacksAndMessages(null);
    if (popup != null) popup.dismiss();
    if (speedDialog != null) speedDialog.dismiss();
    controls.animate().cancel();
    if (player != null) player.removeListener(playerListener);
    playerView.setPlayer(null);
    player = null;
    restoreBrightness();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    restartProgressUpdates();
    scheduleHide();
  }

  @Override
  protected void onDetachedFromWindow() {
    cancelGestures();
    handler.removeCallbacks(progressTick);
    handler.removeCallbacks(hideControls);
    handler.removeCallbacks(hideHud);
    if (popup != null) popup.dismiss();
    if (speedDialog != null) speedDialog.dismiss();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    updateResponsiveControls(w);
  }

  private void updateResponsiveControls(int width) {
    int available = width > 0 ? width : getResources().getDisplayMetrics().widthPixels;
    downloadButton.setVisibility(downloadVisible && available >= dp(220) ? VISIBLE : GONE);
    // Both edge rows stay 48 dp even in a 96 dp-high page element. Less common actions
    // move to the menu on narrow elements instead of shrinking their touch targets.
    currentTime.setVisibility(available >= dp(300) ? VISIBLE : GONE);
    durationTime.setVisibility(available >= dp(520) ? VISIBLE : GONE);
    speedButton.setVisibility(available >= dp(360) ? VISIBLE : GONE);
    qualityButton.setVisibility(available >= dp(320) ? VISIBLE : GONE);
    muteButton.setVisibility(available >= dp(520) ? VISIBLE : GONE);
    floatingButton.setVisibility(available >= dp(520) ? VISIBLE : GONE);
  }

  private void requestMode(Mode requested) {
    cancelGestures();
    callback.onModeRequested(requested);
  }

  private void requestQuality() {
    if (player == null || locked) return;
    endBoost();
    showControls();
    callback.onSelectQuality();
  }

  private void togglePlayback() {
    if (player == null) return;
    if (player.getPlayerError() != null) callback.onRetry();
    else if (isPlayingIntent()) player.pause();
    else {
      if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
      player.play();
    }
    showControls();
  }

  private void toggleMute() {
    if (player == null) return;
    if (player.getVolume() > 0) {
      volumeBeforeMute = player.getVolume();
      player.setVolume(0);
    } else player.setVolume(volumeBeforeMute > 0 ? volumeBeforeMute : 1f);
    updateState();
    scheduleHide();
  }

  private void showSpeeds() {
    if (player == null || locked) return;
    endBoost();
    menuOpen = true;
    handler.removeCallbacks(hideControls);
    float speed = player.getPlaybackParameters().speed;
    int selected = -1;
    for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - speed) < .01f) selected = i;
    speedDialog =
        new AlertDialog.Builder(getContext())
            .setTitle("播放速度")
            .setSingleChoiceItems(
                SPEED_LABELS,
                selected,
                (dialog, which) -> {
                  if (player != null) player.setPlaybackSpeed(SPEEDS[which]);
                  dialog.dismiss();
                })
            .create();
    speedDialog.setOnDismissListener(
        dialog -> {
          menuOpen = false;
          speedDialog = null;
          scheduleHide();
        });
    speedDialog.show();
  }

  private void showMore() {
    if (locked) return;
    menuOpen = true;
    handler.removeCallbacks(hideControls);
    popup = new PopupMenu(getContext(), moreButton);
    popup.getMenu().add(0, 1, 0, "选择页面视频");
    popup.getMenu().add(0, 9, 1, "画质 · " + QualityOptions.currentLabel(player));
    popup.getMenu().add(0, 2, 2, "播放速度");
    popup.getMenu().add(0, 3, 3, player != null && player.getVolume() == 0 ? "取消静音" : "静音");
    popup.getMenu().add(0, 4, 4, mode == Mode.FLOATING ? "返回网页位置" : "小窗播放");
    if (mode == Mode.FULLSCREEN) popup.getMenu().add(0, 5, 5, "锁定播放控制");
    if (downloadVisible && downloadButton.getVisibility() != VISIBLE)
      popup.getMenu().add(0, 6, 6, "下载视频");
    if (player != null && player.isCurrentMediaItemSeekable() && duration() > 0) {
      popup.getMenu().add(0, 7, 7, "快退 15 秒");
      popup.getMenu().add(0, 8, 8, "快进 15 秒");
    }
    popup.setOnMenuItemClickListener(
        item -> {
          // Popup dismissal precedes the next dialog so it cannot reset that dialog's idle guard.
          int action = item.getItemId();
          post(
              () -> {
                switch (action) {
                  case 1 -> callback.onSelectSource();
                  case 2 -> showSpeeds();
                  case 3 -> toggleMute();
                  case 4 -> requestMode(mode == Mode.FLOATING ? Mode.INLINE : Mode.FLOATING);
                  case 5 -> setLocked(true);
                  case 6 -> callback.onDownload();
                  case 7 -> {
                    if (player != null)
                      player.seekTo(Math.max(0, player.getCurrentPosition() - 15000));
                  }
                  case 8 -> {
                    if (player != null)
                      player.seekTo(Math.min(duration(), player.getCurrentPosition() + 15000));
                  }
                  case 9 -> requestQuality();
                  default -> {}
                }
              });
          return true;
        });
    popup.setOnDismissListener(
        value -> {
          menuOpen = false;
          popup = null;
          scheduleHide();
        });
    popup.show();
  }

  private void updateState() {
    if (released) return;
    boolean intent = isPlayingIntent();
    setButton(playButton, intent ? "pause" : "play", intent ? "暂停" : "播放");
    boolean muted = player != null && player.getVolume() == 0;
    setButton(muteButton, muted ? "muted" : "volume", muted ? "取消静音" : "静音");
    speedButton.setText(speedLabel(player == null ? 1f : player.getPlaybackParameters().speed));
    String quality = QualityOptions.currentLabel(player);
    qualityButton.setText("未知".equals(quality) ? "画质" : quality);
    qualityButton.setContentDescription("选择播放画质，当前" + quality);
    PlaybackException error = player == null ? null : player.getPlayerError();
    errorPanel.setVisibility(error == null || locked ? GONE : VISIBLE);
    if (error != null) {
      errorLabel.setText("播放失败");
      errorLabel.setContentDescription("播放失败，" + error.getErrorCodeName());
    }
    buffering.setVisibility(
        player != null
                && error == null
                && !boosting
                && gesture == 0
                && player.getPlaybackState() == Player.STATE_BUFFERING
            ? VISIBLE
            : GONE);
    if (!intent || error != null) setControlsVisible(!locked);
    else scheduleHide();
    updateProgress();
  }

  private void updateProgress() {
    if (player == null || scrubbing || gesture == 1) return;
    long duration = duration();
    long position = Math.max(0, player.getCurrentPosition());
    currentTime.setText(formatTime(position));
    durationTime.setText(
        duration > 0 ? formatTime(duration) : player.isCurrentMediaItemLive() ? "直播" : "--:--");
    seekBar.setEnabled(duration > 0 && player.isCurrentMediaItemSeekable());
    seekBar.setProgress(
        duration > 0 ? (int) Math.min(SEEK_STEPS, (double) position / duration * SEEK_STEPS) : 0);
    seekBar.setSecondaryProgress(
        duration > 0
            ? (int)
                Math.min(
                    SEEK_STEPS,
                    (double) Math.max(0, player.getBufferedPosition()) / duration * SEEK_STEPS)
            : 0);
    ViewCompat.setStateDescription(
        seekBar, formatTime(position) + " / " + (duration > 0 ? formatTime(duration) : "未知时长"));
  }

  private boolean onVideoTouch(View view, MotionEvent event) {
    if (released) return false;
    if (locked) return true;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN -> {
        touching = true;
        touchX = event.getX();
        touchY = event.getY();
        gesture = 0;
        startPosition = player == null ? 0 : Math.max(0, player.getCurrentPosition());
        seekTarget = startPosition;
        startBrightness = effectiveBrightness();
        startVolume = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(hideHud);
        tapDetector.onTouchEvent(event);
        return true;
      }
      case MotionEvent.ACTION_MOVE -> {
        if (!touching) return true;
        float dx = event.getX() - touchX, dy = event.getY() - touchY;
        if (!boosting && gesture == 0 && Math.hypot(dx, dy) > Math.max(touchSlop, dp(12))) {
          if (Math.abs(dx) > Math.abs(dy) * 1.25f
              && player != null
              && player.isCurrentMediaItemSeekable()
              && duration() > 0) {
            gesture = 1;
            cancelTapRecognition();
            setControlsVisible(true);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
          } else if (mode == Mode.FULLSCREEN && Math.abs(dy) > Math.abs(dx) * 1.25f) {
            gesture = touchX < view.getWidth() / 2f ? 2 : 3;
            cancelTapRecognition();
            setControlsVisible(false);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
          }
        }
        if (gesture == 1) {
          // A full-width swipe covers at most two minutes, preserving fine control in long videos.
          long range = Math.min(duration(), 120000);
          seekTarget =
              Math.max(
                  0,
                  Math.min(
                      duration(),
                      startPosition + Math.round(dx / Math.max(1, view.getWidth()) * range)));
          currentTime.setText(formatTime(seekTarget));
          seekBar.setProgress((int) (seekTarget * (double) SEEK_STEPS / duration()));
          long delta = (seekTarget - startPosition) / 1000;
          showHud(
              formatTime(seekTarget)
                  + " / "
                  + formatTime(duration())
                  + "\n"
                  + (delta > 0 ? "+" : "")
                  + delta
                  + " 秒");
        } else if (gesture == 2) {
          float value =
              Math.max(
                  .02f,
                  Math.min(1f, startBrightness - dy / Math.max(dp(160), view.getHeight()) * 1.2f));
          setWindowBrightness(value);
          showHud("亮度 " + Math.round(value * 100) + "%");
        } else if (gesture == 3 && audio != null) {
          int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
          int volume =
              Math.max(
                  0,
                  Math.min(
                      max,
                      startVolume - Math.round(dy / Math.max(dp(160), view.getHeight()) * max)));
          audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
          if (volume > 0 && player != null && player.getVolume() == 0)
            player.setVolume(volumeBeforeMute);
          showHud("音量 " + Math.round(volume * 100f / Math.max(1, max)) + "%");
        } else if (!boosting) tapDetector.onTouchEvent(event);
        return true;
      }
      case MotionEvent.ACTION_UP -> {
        if (!touching) return true;
        touching = false;
        int completed = gesture;
        if (completed == 1 && player != null) player.seekTo(seekTarget);
        if (boosting) {
          endBoost();
          cancelTapRecognition();
        } else if (completed == 0) tapDetector.onTouchEvent(event);
        else cancelTapRecognition();
        gesture = 0;
        if (completed != 0) handler.postDelayed(hideHud, 500);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        updateProgress();
        scheduleHide();
        return true;
      }
      case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
        cancelGestures();
        scheduleHide();
        return true;
      }
      default -> {
        return true;
      }
    }
  }

  private boolean onTitleTouch(View view, MotionEvent event) {
    if (mode != Mode.FLOATING || locked) return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN -> {
        dragX = event.getRawX();
        dragY = event.getRawY();
        handler.removeCallbacks(hideControls);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
      }
      case MotionEvent.ACTION_MOVE -> {
        float x = event.getRawX(), y = event.getRawY();
        callback.onDrag(x - dragX, y - dragY);
        dragX = x;
        dragY = y;
        return true;
      }
      case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        scheduleHide();
        return true;
      }
      default -> {
        return true;
      }
    }
  }

  private void cancelGestures() {
    touching = false;
    scrubbing = false;
    gesture = 0;
    endBoost();
    if (tapDetector != null) cancelTapRecognition();
    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    if (gestureHud != null) gestureHud.setVisibility(GONE);
    handler.removeCallbacks(hideHud);
  }

  private void cancelTapRecognition() {
    long now = SystemClock.uptimeMillis();
    MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, touchX, touchY, 0);
    tapDetector.onTouchEvent(cancel);
    cancel.recycle();
  }

  private void endBoost() {
    if (!boosting) return;
    boosting = false;
    if (player != null) player.setPlaybackSpeed(speedBeforeBoost);
    gestureHud.setVisibility(GONE);
  }

  private void showHud(String text) {
    handler.removeCallbacks(hideHud);
    gestureHud.setText(text);
    gestureHud.setVisibility(VISIBLE);
    buffering.setVisibility(GONE);
  }

  private void hideGestureHud() {
    gestureHud.setVisibility(GONE);
  }

  private void setControlsVisible(boolean visible) {
    visible = visible && !locked;
    boolean changed = controlsVisible != visible;
    controlsVisible = visible;
    handler.removeCallbacks(hideControls);
    controls.animate().cancel();
    lockButton.setVisibility(mode == Mode.FULLSCREEN && (visible || locked) ? VISIBLE : GONE);
    if (!changed || !ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
      controls.setAlpha(visible ? 1f : 0f);
      controls.setVisibility(visible ? VISIBLE : GONE);
      return;
    }
    if (visible) controls.setVisibility(VISIBLE);
    controls
        .animate()
        .alpha(visible ? 1f : 0f)
        .setDuration(visible ? 140 : 180)
        .setInterpolator(EASE_OUT)
        .withEndAction(
            () -> {
              if (!controlsVisible) controls.setVisibility(GONE);
            })
        .start();
  }

  private boolean canAutoHide() {
    return isPlayingIntent()
        && !scrubbing
        && !touching
        && !boosting
        && !menuOpen
        && !locked
        && (accessibility == null || !accessibility.isTouchExplorationEnabled());
  }

  private void scheduleHide() {
    handler.removeCallbacks(hideControls);
    if (!released && controlsVisible && canAutoHide())
      handler.postDelayed(hideControls, HIDE_DELAY_MS);
  }

  private void restartProgressUpdates() {
    handler.removeCallbacks(progressTick);
    if (!released && isAttachedToWindow()) handler.post(progressTick);
  }

  private boolean isPlayingIntent() {
    return player != null
        && player.getPlayWhenReady()
        && player.getPlaybackState() != Player.STATE_ENDED
        && player.getPlayerError() == null;
  }

  private long duration() {
    long value = player == null ? 0 : player.getDuration();
    return value == C.TIME_UNSET || value < 0 ? 0 : value;
  }

  private float effectiveBrightness() {
    if (activity == null) return .5f;
    float value = activity.getWindow().getAttributes().screenBrightness;
    if (value >= 0) return value;
    return Settings.System.getInt(
            getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128)
        / 255f;
  }

  private void setWindowBrightness(float value) {
    if (activity == null) return;
    WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
    if (!changedBrightness) {
      originalBrightness = attributes.screenBrightness;
      changedBrightness = true;
    }
    attributes.screenBrightness = value;
    activity.getWindow().setAttributes(attributes);
  }

  private void restoreBrightness() {
    if (!changedBrightness || activity == null) return;
    WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
    attributes.screenBrightness = originalBrightness;
    activity.getWindow().setAttributes(attributes);
    changedBrightness = false;
  }

  private static Activity findActivity(Context context) {
    while (context instanceof ContextWrapper) {
      if (context instanceof Activity) return (Activity) context;
      Context base = ((ContextWrapper) context).getBaseContext();
      if (base == context) break;
      context = base;
    }
    return null;
  }

  private TextView label(String text, float size) {
    TextView value = Ui.text(getContext(), text, size, WHITE);
    value.setShadowLayer(dp(2), 0, dp(1), 0xb3000000);
    return value;
  }

  private TextView textButton(String text, String description, Runnable click) {
    TextView view = label(text, 13);
    view.setGravity(Gravity.CENTER);
    view.setMinWidth(dp(48));
    view.setMinHeight(dp(48));
    view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    view.setContentDescription(description);
    view.setTooltipText(description);
    view.setBackground(controlRipple());
    view.setFocusable(true);
    view.setOnClickListener(
        v -> {
          click.run();
          scheduleHide();
        });
    return view;
  }

  private ImageButton icon(String name, String description, Runnable click) {
    ImageButton button = new ImageButton(getContext());
    button.setPadding(dp(12), dp(12), dp(12), dp(12));
    button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    button.setMinimumWidth(dp(48));
    button.setMinimumHeight(dp(48));
    button.setBackground(controlRipple());
    button.setElevation(0);
    button.setStateListAnimator(null);
    setButton(button, name, description);
    button.setOnClickListener(
        v -> {
          click.run();
          scheduleHide();
        });
    return button;
  }

  private void setButton(ImageButton button, String name, String description) {
    Object current = button.getTag();
    if (!name.equals(current)) {
      boolean local =
          name.equals("lock")
              || name.equals("unlock")
              || name.equals("float")
              || name.equals("inline")
              || name.equals("volume")
              || name.equals("muted");
      button.setImageDrawable(local ? new ControlIcon(name) : new Ui.Icon(name, WHITE));
      button.setTag(name);
    }
    button.setContentDescription(description);
    button.setTooltipText(description);
  }

  private RippleDrawable controlRipple() {
    return new RippleDrawable(
        ColorStateList.valueOf(0x35ffffff), null, Ui.shape(getContext(), WHITE, 8));
  }

  private LinearLayout.LayoutParams buttonParams() {
    return new LinearLayout.LayoutParams(dp(48), dp(48));
  }

  private int dp(float value) {
    return Ui.dp(getContext(), value);
  }

  private static String formatTime(long millis) {
    long seconds = Math.max(0, millis / 1000);
    if (seconds >= 3600)
      return String.format(
          Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
  }

  private static String speedLabel(float speed) {
    return (speed * 10 == Math.round(speed * 10)
            ? String.format(Locale.ROOT, "%.1f", speed)
            : String.format(Locale.ROOT, "%.2f", speed))
        + "×";
  }

  /** Small original vector controls using the same 24-unit stroke system as MediaMaster. */
  private static final class ControlIcon extends Drawable {
    private final String name;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ControlIcon(String name) {
      this.name = name;
      paint.setColor(WHITE);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.8f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
      canvas.save();
      canvas.translate(getBounds().left, getBounds().top);
      canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
      if (name.equals("lock") || name.equals("unlock")) {
        canvas.drawRoundRect(5, 10, 19, 21, 2, 2, paint);
        canvas.drawLine(12, 14, 12, 17, paint);
        Path shackle = new Path();
        shackle.moveTo(8, 10);
        shackle.lineTo(8, 7);
        shackle.cubicTo(8, 1.5f, 16, 1.5f, 16, 7);
        if (name.equals("lock")) shackle.lineTo(16, 10);
        canvas.drawPath(shackle, paint);
      } else if (name.equals("float") || name.equals("inline")) {
        canvas.drawRoundRect(3, 4, 21, 20, 2, 2, paint);
        if (name.equals("float")) {
          paint.setStyle(Paint.Style.FILL);
          canvas.drawRoundRect(11, 11, 18, 17, 1, 1, paint);
          paint.setStyle(Paint.Style.STROKE);
        } else {
          canvas.drawLine(15, 15, 8, 8, paint);
          canvas.drawLine(8, 8, 8, 13, paint);
          canvas.drawLine(8, 8, 13, 8, paint);
        }
      } else {
        Path speaker = new Path();
        speaker.moveTo(3, 9);
        speaker.lineTo(7, 9);
        speaker.lineTo(12, 5);
        speaker.lineTo(12, 19);
        speaker.lineTo(7, 15);
        speaker.lineTo(3, 15);
        speaker.close();
        canvas.drawPath(speaker, paint);
        if (name.equals("muted")) {
          canvas.drawLine(16, 9, 21, 15, paint);
          canvas.drawLine(21, 9, 16, 15, paint);
        } else {
          canvas.drawArc(new RectF(12, 7, 20, 17), -65, 130, false, paint);
          canvas.drawArc(new RectF(11, 3, 24, 21), -60, 120, false, paint);
        }
      }
      canvas.restore();
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
