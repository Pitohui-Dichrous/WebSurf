package io.websurf.app.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A single, persistent, foreground download queue. User cancellation is also in the notification.
 */
public final class DownloadService extends Service {
  public static final String ACTION_CHANGED = "io.websurf.app.DOWNLOAD_CHANGED";
  private static final String CHANNEL = "websurf_downloads";
  private static final int NOTIFICATION = 4201;
  private static final String CANCEL = "io.websurf.app.DOWNLOAD_CANCEL";
  private static final String START = "io.websurf.app.DOWNLOAD_START";
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile DownloadEngine engine;
  private volatile String runningId;
  private boolean busy;
  private boolean foreground;
  private volatile boolean destroyed;
  private int lastStartId;

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
    launch(context, job.id);
    return job.id;
  }

  public static void cancel(Context context, String id) {
    DownloadStore.Job job = DownloadStore.get(context, id);
    if (job == null || !job.active()) return;
    DownloadStore.changeState(context, id, "cancelled", "");
    try {
      context.startService(
          new Intent(context, DownloadService.class).setAction(CANCEL).putExtra("id", id));
    } catch (RuntimeException ignored) {
    }
  }

  public static void retry(Context context, String id) {
    DownloadStore.Job job = DownloadStore.get(context, id);
    if (job == null || job.active() || "completed".equals(job.state)) return;
    job.state = "queued";
    job.error = "";
    job.outputUri = "";
    job.progress = 0;
    job.bytes = 0;
    job.total = -1;
    job.detail = "等待下载";
    DownloadStore.put(context, job);
    launch(context, id);
  }

  /**
   * Call on app entry to make a process interruption visible; explicit retry restarts the request.
   */
  public static void recoverInterrupted(Context context) {
    if (isRunning) return;
    for (DownloadStore.Job j : DownloadStore.list(context))
      if (j.active()) {
        DownloadStore.changeState(context, j.id, "failed", "下载被系统中断，点击重试可重新下载");
      }
  }

  private static volatile boolean isRunning;

  private static void launch(Context context, String id) {
    try {
      context.startForegroundService(new Intent(context, DownloadService.class).setAction(START));
    } catch (RuntimeException e) {
      DownloadStore.changeState(context, id, "failed", "无法启动后台下载：" + e.getClass().getSimpleName());
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    isRunning = true;
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW));
    // Work that outlived its previous process is restarted from the beginning, never marked
    // complete.
    for (DownloadStore.Job j : DownloadStore.list(this))
      if ("downloading".equals(j.state) || "converting".equals(j.state)) {
        DownloadStore.changeState(this, j.id, "queued", "");
      }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    lastStartId = startId;
    if (intent != null && CANCEL.equals(intent.getAction())) {
      String id = intent.getStringExtra("id");
      DownloadStore.Job job = DownloadStore.get(this, id);
      if (job != null && job.active()) DownloadStore.changeState(this, id, "cancelled", "");
      DownloadEngine current = engine;
      if (id != null && id.equals(runningId) && current != null) current.cancel();
      broadcast();
    }
    if (!foreground) {
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
    // list is newest-first; queue is FIFO.
    java.util.List<DownloadStore.Job> jobs = DownloadStore.list(this);
    for (int i = jobs.size() - 1; i >= 0; i--)
      if ("queued".equals(jobs.get(i).state)) {
        next = jobs.get(i);
        break;
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
    engine =
        new DownloadEngine(
            this,
            job,
            current -> {
              if (!DownloadStore.updateProgress(this, current)) throw new CancellationException();
              getSystemService(NotificationManager.class)
                  .notify(NOTIFICATION, notification(current));
              broadcast();
            });
    DownloadEngine taskEngine = engine;
    executor.execute(
        () -> {
          try {
            taskEngine.run();
          } catch (CancellationException e) {
            DownloadStore.Job saved = DownloadStore.get(this, job.id);
            if (saved != null && !"failed".equals(saved.state))
              DownloadStore.changeState(this, job.id, "cancelled", "");
          } catch (Exception e) {
            DownloadStore.Job saved = DownloadStore.get(this, job.id);
            if (saved != null && !"cancelled".equals(saved.state)) {
              String detail = e.getMessage();
              if (detail == null || detail.trim().isEmpty()) detail = e.getClass().getSimpleName();
              DownloadStore.changeState(this, job.id, "failed", detail);
            }
          } finally {
            broadcast();
            main.post(
                () -> {
                  engine = null;
                  runningId = null;
                  busy = false;
                  schedule();
                });
          }
        });
  }

  private void broadcast() {
    sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName()));
  }

  private Notification notification(DownloadStore.Job job) {
    Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
    if (open == null) open = new Intent();
    open.putExtra("show_downloads", true);
    Notification.Builder builder =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job == null ? "WebSurf 下载" : job.title)
            .setContentText(job == null ? "准备下载" : job.detail)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    4201,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS);
    if (job != null) {
      builder.setProgress(100, job.progress, job.progress == 0 && job.total < 0);
      Intent cancel =
          new Intent(this, DownloadService.class).setAction(CANCEL).putExtra("id", job.id);
      builder.addAction(
          new Notification.Action.Builder(
                  null,
                  "取消",
                  PendingIntent.getService(
                      this,
                      job.id.hashCode(),
                      cancel,
                      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
              .build());
    }
    return builder.build();
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    isRunning = false;
    DownloadEngine current = engine;
    if (current != null) current.cancel();
    executor.shutdownNow();
    super.onDestroy();
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    DownloadEngine current = engine;
    if (current != null) current.cancel();
    if (runningId != null)
      DownloadStore.changeState(this, runningId, "failed", "系统限制了后台下载时长，请回到应用重试");
    stopSelf();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
