package io.websurf.app.download;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import io.websurf.app.MainActivity;
import io.websurf.app.R;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent FIFO tasks; each task may use the engine's bounded concurrent transfers. */
public final class DownloadService extends Service {
  public static final String ACTION_CHANGED = "io.websurf.app.DOWNLOAD_CHANGED";
  public static final String EXTRA_ID = "id";
  public static final String EXTRA_STATE = "state";
  private static final String CHANNEL = "websurf_downloads";
  private static final String RESULT_CHANNEL = "websurf_download_results";
  private static final int NOTIFICATION = 4201;
  private static final int RESULT_NOTIFICATION = 4202;
  private static final String CANCEL = "io.websurf.app.DOWNLOAD_CANCEL";
  private static final String RETRY = "io.websurf.app.DOWNLOAD_RETRY";
  private static final String START = "io.websurf.app.DOWNLOAD_START";
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Set<String> announcedTerminals = ConcurrentHashMap.newKeySet();
  private final Set<String> pendingRetries = ConcurrentHashMap.newKeySet();
  private volatile DownloadEngine engine;
  private volatile String runningId;
  private boolean busy;
  private boolean foreground;
  private volatile boolean destroyed;
  private int lastStartId;
  private static volatile boolean isRunning;
  private static volatile DownloadService liveService;

  public static String enqueue(
      Context context, String url, String title, Map<String, String> headers) {
    return enqueue(context, url, title, headers, "");
  }

  public static String enqueue(
      Context context, String url, String title, Map<String, String> headers, String mimeHint) {
    DownloadStore.Job job = new DownloadStore.Job();
    job.id = UUID.randomUUID().toString();
    job.url = url;
    job.title = title == null || title.trim().isEmpty() ? "WebSurf 下载" : title;
    job.mimeType = mimeHint == null ? "" : mimeHint;
    job.createdAt = System.currentTimeMillis();
    if (headers != null) job.headers.putAll(headers);
    DownloadStore.put(context, job);
    if (launch(context, job.id)) broadcast(context, job.id, "queued");
    return job.id;
  }

  public static void cancel(Context context, String id) {
    DownloadStore.Job job = DownloadStore.get(context, id);
    if (job == null) return;
    DownloadService current = liveService;
    if (!job.active()) {
      if (current != null) current.main.post(() -> current.pendingRetries.remove(id));
      return;
    }
    DownloadStore.changeState(context, id, "cancelled", "");
    dismissResult(context, id);
    broadcast(context, id, "cancelled");
    if (current != null) current.main.post(() -> current.cancelRunning(id));
    // A not-yet-started or stopped service has no engine to interrupt. Persisted cancellation
    // is sufficient, and avoids a delayed CANCEL intent cancelling a later retry of the same ID.
  }

  private void cancelRunning(String id) {
    pendingRetries.remove(id);
    DownloadEngine current = engine;
    if (id.equals(runningId) && current != null) current.cancel();
  }

  public static void retry(Context context, String id) {
    DownloadService current = liveService;
    if (current != null) {
      current.main.post(() -> current.requestRetry(id, true));
    } else if (queueRetry(context, id) && launch(context, id)) {
      broadcast(context, id, "queued");
    }
  }

  /** Main-thread gate: an old cancelled run must finish before its ID becomes active again. */
  private void requestRetry(String id, boolean launchService) {
    DownloadStore.Job job = DownloadStore.get(this, id);
    if (job == null || job.active() || "completed".equals(job.state)) return;
    if (id.equals(runningId)) {
      pendingRetries.add(id);
      return;
    }
    if (queueRetry(this, id) && (!launchService || launch(this, id))) broadcast(this, id, "queued");
  }

  private static boolean queueRetry(Context context, String id) {
    DownloadStore.Job job = DownloadStore.get(context, id);
    if (job == null || job.active() || "completed".equals(job.state)) return false;
    job.state = "queued";
    job.error = "";
    job.outputUri = "";
    job.progress = 0;
    job.bytes = 0;
    job.total = -1;
    job.bytesPerSecond = 0;
    job.connections = 0;
    job.completedParts = 0;
    job.totalParts = 0;
    job.detail = "等待下载";
    DownloadStore.put(context, job);
    dismissResult(context, id);
    return true;
  }

  /** An interrupted transfer is visible and explicitly retryable, never marked complete. */
  public static void recoverInterrupted(Context context) {
    if (isRunning) return;
    for (DownloadStore.Job j : DownloadStore.list(context)) {
      if (j.active()) {
        DownloadStore.changeState(context, j.id, "failed", "下载被系统中断，点击重试可重新下载");
        broadcast(context, j.id, "failed");
      }
    }
  }

  private static boolean launch(Context context, String id) {
    try {
      context.startForegroundService(
          new Intent(context, DownloadService.class).setAction(START).putExtra(EXTRA_ID, id));
      return true;
    } catch (RuntimeException e) {
      DownloadStore.changeState(context, id, "failed", "无法启动后台下载：" + e.getClass().getSimpleName());
      DownloadStore.Job failed = DownloadStore.get(context, id);
      if (failed != null) notifyResult(context, failed, false);
      broadcast(context, id, "failed");
      return false;
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    liveService = this;
    isRunning = true;
    createChannels(this);
    for (DownloadStore.Job j : DownloadStore.list(this)) {
      if ("downloading".equals(j.state) || "converting".equals(j.state)) {
        DownloadStore.changeState(this, j.id, "queued", "");
      }
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    lastStartId = startId;
    if (intent != null && CANCEL.equals(intent.getAction())) {
      String id = intent.getStringExtra(EXTRA_ID);
      DownloadStore.Job job = DownloadStore.get(this, id);
      if (job != null && job.active()) DownloadStore.changeState(this, id, "cancelled", "");
      if (id != null) cancelRunning(id);
      if (id != null) {
        dismissResult(this, id);
        broadcast(this, id, "cancelled");
      }
    } else if (intent != null && RETRY.equals(intent.getAction())) {
      String id = intent.getStringExtra(EXTRA_ID);
      if (id != null) requestRetry(id, false);
    }
    if (!foreground) {
      // POST_NOTIFICATIONS is not required to run an FGS. Always supply its notification;
      // ordinary start/result notifications below independently tolerate denied permission.
      Notification n = notification(null);
      if (Build.VERSION.SDK_INT >= 29)
        startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
      else startForeground(NOTIFICATION, n);
      foreground = true;
    }
    schedule();
    return START_NOT_STICKY;
  }

  private void schedule() {
    if (busy || destroyed) return;
    DownloadStore.Job next = null;
    java.util.List<DownloadStore.Job> jobs = DownloadStore.list(this);
    for (int i = jobs.size() - 1; i >= 0; i--) {
      if ("queued".equals(jobs.get(i).state)) {
        next = jobs.get(i);
        break;
      }
    }
    if (next == null) {
      if (foreground) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
      }
      stopSelfResult(lastStartId);
      return;
    }
    busy = true;
    DownloadStore.Job job = next;
    runningId = job.id;
    announcedTerminals.remove(job.id);
    AtomicBoolean started = new AtomicBoolean();
    engine =
        new DownloadEngine(
            this,
            job,
            current -> {
              if (!DownloadStore.updateProgress(this, current)) throw new CancellationException();
              if ("downloading".equals(current.state) && started.compareAndSet(false, true))
                notifyResult(this, current, true);
              if (current.active()) notifySafely(this, null, NOTIFICATION, notification(current));
              broadcast(this, current.id, current.state);
            });
    DownloadEngine taskEngine = engine;
    executor.execute(
        () -> {
          try {
            taskEngine.run();
          } catch (CancellationException e) {
            DownloadStore.Job saved = DownloadStore.get(this, job.id);
            if (saved != null && saved.active())
              DownloadStore.changeState(this, job.id, "cancelled", "");
          } catch (Exception e) {
            DownloadStore.Job saved = DownloadStore.get(this, job.id);
            if (saved != null && saved.active()) {
              String detail = e.getMessage();
              if (detail == null || detail.trim().isEmpty()) detail = e.getClass().getSimpleName();
              DownloadStore.changeState(this, job.id, "failed", detail);
            }
          } finally {
            DownloadStore.Job finished = DownloadStore.get(this, job.id);
            if (finished != null) {
              announceTerminal(finished);
              broadcast(this, finished.id, finished.state);
            }
            main.post(
                () -> {
                  engine = null;
                  runningId = null;
                  busy = false;
                  if (pendingRetries.remove(job.id) && queueRetry(this, job.id)) {
                    if (!destroyed || launch(this, job.id)) broadcast(this, job.id, "queued");
                  }
                  schedule();
                });
          }
        });
  }

  private void announceTerminal(DownloadStore.Job job) {
    if (("completed".equals(job.state) || "failed".equals(job.state))
        && announcedTerminals.add(job.id)) notifyResult(this, job, false);
    else if ("cancelled".equals(job.state)) dismissResult(this, job.id);
  }

  private static void broadcast(Context context, String id, String state) {
    context.sendBroadcast(
        new Intent(ACTION_CHANGED)
            .setPackage(context.getPackageName())
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_STATE, state));
  }

  private static void createChannels(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return;
    try {
      manager.createNotificationChannel(
          new NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW));
      manager.createNotificationChannel(
          new NotificationChannel(RESULT_CHANNEL, "下载结果", NotificationManager.IMPORTANCE_DEFAULT));
    } catch (SecurityException ignored) {
      // Managed or denied notifications must not turn a valid media transfer into a failure.
    }
  }

  private static PendingIntent openDownloads(Context context, String id) {
    Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
    if (open == null) open = new Intent(context, MainActivity.class);
    open.setAction(context.getPackageName() + ".SHOW_DOWNLOADS." + (id == null ? "queue" : id));
    open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    open.putExtra("show_downloads", true);
    if (id != null) open.putExtra("download_id", id).putExtra(EXTRA_ID, id);
    return PendingIntent.getActivity(
        context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static PendingIntent taskAction(Context context, String id, String action) {
    Intent command =
        new Intent(context, DownloadService.class)
            .setAction(action)
            .setData(Uri.fromParts("websurf-download", id, null))
            .putExtra(EXTRA_ID, id);
    return PendingIntent.getForegroundService(
        context, 0, command, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private Notification notification(DownloadStore.Job job) {
    Notification.Builder builder =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ws_lucide_download)
            .setContentTitle(job == null ? "下载" : title(job))
            .setContentText(job == null ? "准备下载" : job.detail)
            .setContentIntent(openDownloads(this, job == null ? null : job.id))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_PROGRESS);
    if (job != null) {
      builder.setProgress(100, job.progress, job.progress <= 0 && job.total < 0);
      builder.addAction(
          new Notification.Action.Builder(null, "取消", taskAction(this, job.id, CANCEL)).build());
    }
    return builder.build();
  }

  private static String title(DownloadStore.Job job) {
    return job.fileName == null || job.fileName.isBlank() ? job.title : job.fileName;
  }

  private static void notifyResult(Context context, DownloadStore.Job job, boolean starting) {
    createChannels(context);
    boolean failed = "failed".equals(job.state);
    String status = starting ? "开始下载" : failed ? "下载失败" : "下载完成";
    String content = title(job);
    String full = failed && !job.error.isBlank() ? content + "\n" + job.error : content;
    Notification.Builder builder =
        new Notification.Builder(context, RESULT_CHANNEL)
            .setSmallIcon(
                starting
                    ? R.drawable.ws_lucide_download
                    : failed ? R.drawable.ws_lucide_circle_alert : R.drawable.ws_lucide_check)
            .setContentTitle(status)
            .setContentText(content)
            .setStyle(new Notification.BigTextStyle().bigText(full))
            .setContentIntent(openDownloads(context, job.id))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(
                failed
                    ? Notification.CATEGORY_ERROR
                    : starting ? Notification.CATEGORY_PROGRESS : Notification.CATEGORY_STATUS);
    if (starting)
      builder.addAction(
          new Notification.Action.Builder(null, "取消", taskAction(context, job.id, CANCEL)).build());
    else if (failed)
      builder.addAction(
          new Notification.Action.Builder(null, "重试", taskAction(context, job.id, RETRY)).build());
    notifySafely(context, "download-" + job.id, RESULT_NOTIFICATION, builder.build());
  }

  private static void notifySafely(Context context, String tag, int id, Notification notification) {
    if (Build.VERSION.SDK_INT >= 33
        && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null || !manager.areNotificationsEnabled()) return;
    try {
      manager.notify(tag, id, notification);
    } catch (SecurityException ignored) {
      // Permission can be withdrawn between checking and posting; the task still succeeds.
    }
  }

  private static void dismissResult(Context context, String id) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return;
    try {
      manager.cancel("download-" + id, RESULT_NOTIFICATION);
    } catch (SecurityException ignored) {
    }
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    if (liveService == this) liveService = null;
    isRunning = false;
    DownloadEngine current = engine;
    if (current != null) current.cancel();
    executor.shutdownNow();
    super.onDestroy();
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    DownloadStore.Job job = runningId == null ? null : DownloadStore.get(this, runningId);
    if (job != null && job.active()) {
      DownloadStore.changeState(this, job.id, "failed", "系统限制了后台下载时长，请回到应用重试");
      DownloadStore.Job failed = DownloadStore.get(this, job.id);
      if (failed != null) {
        announceTerminal(failed);
        broadcast(this, failed.id, failed.state);
      }
    }
    DownloadEngine current = engine;
    if (current != null) current.cancel();
    stopSelf();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
