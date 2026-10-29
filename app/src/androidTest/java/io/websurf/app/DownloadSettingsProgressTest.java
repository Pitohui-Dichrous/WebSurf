package io.websurf.app;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.websurf.app.download.DownloadService;
import io.websurf.app.download.DownloadStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** A queued dismiss callback from settings must not stop a newer download panel's refresh. */
@RunWith(AndroidJUnit4.class)
public final class DownloadSettingsProgressTest {
  private Context context;
  private ActivityScenario<MainActivity> scenario;
  private SharedPreferences prefs, appearance;
  private boolean hadAuto, oldAuto, hadThreads, hadDark, oldDark;
  private int oldThreads;
  private Fixture server;
  private BroadcastReceiver receiver;
  private final List<String> owned = new ArrayList<>();
  private final List<Intent> changes = new CopyOnWriteArrayList<>();

  @Before
  public void openBrowserWithFourConnectionDefault() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    hadAuto = prefs.contains("auto_play");
    oldAuto = prefs.getBoolean("auto_play", true);
    hadThreads = prefs.contains("download_threads");
    oldThreads = prefs.getInt("download_threads", 8);
    hadDark = appearance.contains("dark");
    oldDark = appearance.getBoolean("dark", true);
    assertTrue(prefs.edit().putBoolean("auto_play", false).putInt("download_threads", 4).commit());
    receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context ignored, Intent intent) {
            changes.add(new Intent(intent));
          }
        };
    ContextCompat.registerReceiver(
        context,
        receiver,
        new IntentFilter(DownloadService.ACTION_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED);
    server = new Fixture();
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class).setData(Uri.parse(server.base() + "/page")));
    await(
        "Browser did not become visible",
        10000,
        () -> ui(a -> a.resumed && a.current.web.getProgress() == 100));
  }

  @After
  public void restoreOnlyOwnedTransfersAndPreferences() throws Exception {
    if (server != null) {
      server.first.release.countDown();
      server.next.release.countDown();
    }
    for (String id : owned) {
      DownloadStore.Job job = DownloadStore.get(context, id);
      if (job != null && job.active()) DownloadService.cancel(context, id);
    }
    if (server != null) server.close();
    if (scenario != null) scenario.close();
    for (String id : owned) {
      DownloadStore.Job job = DownloadStore.get(context, id);
      if (job != null && !job.outputUri.isBlank())
        context.getContentResolver().delete(Uri.parse(job.outputUri), null, null);
      DownloadStore.remove(context, id);
      context.getSystemService(NotificationManager.class).cancel("download-" + id, 4202);
    }
    if (receiver != null) context.unregisterReceiver(receiver);
    if (prefs != null) {
      SharedPreferences.Editor edit = prefs.edit();
      if (hadAuto) edit.putBoolean("auto_play", oldAuto);
      else edit.remove("auto_play");
      if (hadThreads) edit.putInt("download_threads", oldThreads);
      else edit.remove("download_threads");
      assertTrue(edit.commit());
    }
    if (appearance != null) {
      SharedPreferences.Editor edit = appearance.edit();
      if (hadDark) edit.putBoolean("dark", oldDark);
      else edit.remove("dark");
      assertTrue(edit.commit());
      Ui.configure(context);
    }
  }

  @Test
  public void changingConnectionSettingKeepsCurrentTransferAndReopenedProgressLive()
      throws Exception {
    String title = "设置往返下载 " + UUID.randomUUID();
    String id = enqueue(server.base() + "/first.bin", title);
    assertTrue(
        "Four actual range workers did not connect",
        server.first.started.await(10, TimeUnit.SECONDS));
    await(
        "Paced download did not publish its initial bytes",
        10000,
        () -> {
          DownloadStore.Job job = current(id);
          return job.bytes >= 128 * 1024 && "downloading".equals(job.state);
        });

    for (boolean dark : new boolean[] {true, false}) {
      DownloadTaskRow row =
          ui(
              a -> {
                Ui.setDark(a, dark);
                a.applyChromeTheme();
                a.showDownloads();
                // Dialog dismiss listeners run later on the main queue. Replacing all panels in
                // this
                // one dispatch deterministically lets an old listener encounter the newest updater.
                a.showSettings();
                View choose =
                    findTextContaining(a.activeSheet.getWindow().getDecorView(), "下载并发 ·");
                assertNotNull("Concurrency settings action is missing", choose);
                assertTrue(choose.performClick());
                View sixteen = findText(a.activeSheet.getWindow().getDecorView(), "16 路");
                assertNotNull(sixteen);
                assertTrue(sixteen.performClick());
                assertEquals(16, a.prefs.getInt("download_threads", 0));
                a.showDownloads();
                DownloadTaskRow result = findRow(a.activeSheet.getWindow().getDecorView(), title);
                assertNotNull(result);
                return result;
              });
      Dialog owner = ui(a -> a.activeSheet);
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      long beforeBytes = current(id).bytes;
      int beforeProgress = ui(a -> progress(row));
      server.first.release.countDown();
      SystemClock.sleep(1100);
      assertTrue(
          "The existing transfer stopped when its setting changed",
          current(id).bytes > beforeBytes);
      await(
          "Reopened download panel stopped refreshing after the settings dialog was dismissed",
          4000,
          () -> ui(a -> progress(row) > beforeProgress));
      ui(
          a -> {
            assertSame("Progress updates replaced the open panel", owner, a.activeSheet);
            assertSame(
                "Progress updates replaced its task row",
                row,
                findRow(a.activeSheet.getWindow().getDecorView(), title));
            return null;
          });
      assertEquals(
          "Changing the default must not restart the active task",
          1,
          server.first.fullRequests.get());
      assertEquals(
          "The active task keeps its original four-connection cap",
          4,
          server.first.rangeRequests.get());
      assertEquals(4, server.first.peak.get());
      UiCaptureTest.shot(context, "download-settings-progress-" + (dark ? "dark" : "light"));
    }

    DownloadStore.Job finished = complete(id);
    assertOutput(finished);
    assertEquals(1, DownloadStore.list(context).stream().filter(j -> id.equals(j.id)).count());
    assertTrue(
        changes.stream()
            .noneMatch(
                i ->
                    id.equals(i.getStringExtra(DownloadService.EXTRA_ID))
                        && ("failed".equals(i.getStringExtra(DownloadService.EXTRA_STATE))
                            || "cancelled".equals(i.getStringExtra(DownloadService.EXTRA_STATE)))));
    await(
        "Completion event missing",
        5000,
        () ->
            changes.stream()
                .anyMatch(
                    i ->
                        id.equals(i.getStringExtra(DownloadService.EXTRA_ID))
                            && "completed".equals(i.getStringExtra(DownloadService.EXTRA_STATE))));
    String next = enqueue(server.base() + "/next.bin", "新设置下载 " + UUID.randomUUID());
    assertTrue(
        "A subsequent download did not use the selected sixteen-connection cap",
        server.next.started.await(15, TimeUnit.SECONDS));
    assertEquals(16, server.next.rangeRequests.get());
    assertEquals(16, server.next.peak.get());
    server.next.release.countDown();
    assertOutput(complete(next));
    assertEquals(1, server.next.fullRequests.get());
  }

  private String enqueue(String url, String title) {
    String id = DownloadService.enqueue(context, url, title, Map.of(), "application/octet-stream");
    owned.add(id);
    return id;
  }

  private DownloadStore.Job current(String id) {
    DownloadStore.Job job = DownloadStore.get(context, id);
    assertNotNull(job);
    if ("failed".equals(job.state)) fail(job.error);
    assertNotEquals("The active transfer was unexpectedly cancelled", "cancelled", job.state);
    return job;
  }

  private DownloadStore.Job complete(String id) throws Exception {
    await("Download did not finish", 30000, () -> "completed".equals(current(id).state));
    return current(id);
  }

  private void assertOutput(DownloadStore.Job job) throws Exception {
    assertEquals(server.payload.length, job.bytes);
    assertEquals(server.payload.length, job.total);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long bytes = 0;
    try (InputStream input =
        context.getContentResolver().openInputStream(Uri.parse(job.outputUri))) {
      assertNotNull(input);
      byte[] buffer = new byte[64 * 1024];
      int count;
      while ((count = input.read(buffer)) != -1) {
        digest.update(buffer, 0, count);
        bytes += count;
      }
    }
    assertEquals(server.payload.length, bytes);
    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(server.payload), digest.digest());
  }

  private static int progress(View view) {
    CharSequence description = view.getContentDescription();
    if (description != null && description.toString().matches("下载进度 \\d+%"))
      return Integer.parseInt(description.toString().replaceAll("\\D", ""));
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        int result = progress(group.getChildAt(i));
        if (result >= 0) return result;
      }
    return -1;
  }

  private static DownloadTaskRow findRow(View view, String title) {
    if (view instanceof DownloadTaskRow row && findText(row, title) != null) return row;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        DownloadTaskRow result = findRow(group.getChildAt(i), title);
        if (result != null) return result;
      }
    return null;
  }

  private static View findText(View view, String text) {
    if (view instanceof TextView label && text.contentEquals(label.getText())) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View result = findText(group.getChildAt(i), text);
        if (result != null) return result;
      }
    return null;
  }

  private static View findTextContaining(View view, String text) {
    if (view instanceof TextView label && label.getText().toString().contains(text)) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View result = findTextContaining(group.getChildAt(i), text);
        if (result != null) return result;
      }
    return null;
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scenario.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }

  private interface Ready {
    boolean value() throws Exception;
  }

  private void await(String message, long timeoutMs, Ready ready) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeoutMs;
    do {
      if (ready.value()) return;
      SystemClock.sleep(50);
    } while (SystemClock.elapsedRealtime() < end);
    fail(message);
  }

  private static final class Transfer {
    final AtomicInteger fullRequests = new AtomicInteger(),
        rangeRequests = new AtomicInteger(),
        active = new AtomicInteger(),
        peak = new AtomicInteger();
    final CountDownLatch started, release = new CountDownLatch(1);
    final boolean paced;

    Transfer(int connections, boolean paced) {
      started = new CountDownLatch(connections);
      this.paced = paced;
    }
  }

  private static final class Fixture implements AutoCloseable {
    final byte[] payload = new byte[16 * 1024 * 1024];
    final Transfer first = new Transfer(4, true), next = new Transfer(16, false);
    final ServerSocket server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
    final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    final ExecutorService workers = Executors.newCachedThreadPool();
    volatile boolean closed;

    Fixture() throws IOException {
      for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31 + (i >>> 17));
      workers.execute(
          () -> {
            while (!closed)
              try {
                Socket client = server.accept();
                clients.add(client);
                workers.execute(() -> serve(client));
              } catch (IOException failure) {
                if (!closed) throw new RuntimeException(failure);
              }
          });
    }

    String base() {
      return "http://127.0.0.1:" + server.getLocalPort();
    }

    void serve(Socket client) {
      Transfer transfer = null;
      boolean activeRange = false;
      try (Socket socket = client) {
        socket.setSoTimeout(20000);
        BufferedReader in =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String request = in.readLine();
        if (request == null) return;
        String path = request.split(" ")[1];
        String range = null, line;
        while ((line = in.readLine()) != null && !line.isEmpty())
          if (line.toLowerCase(java.util.Locale.ROOT).startsWith("range:"))
            range = line.substring(6).trim();
        OutputStream out = socket.getOutputStream();
        if (!path.equals("/first.bin") && !path.equals("/next.bin")) {
          byte[] page =
              "<!doctype html><title>Download progress fixture</title>"
                  .getBytes(StandardCharsets.UTF_8);
          out.write(
              ("HTTP/1.1 200 OK\r\nContent-Length: "
                      + page.length
                      + "\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
          out.write(page);
          out.flush();
          return;
        }
        transfer = path.equals("/first.bin") ? first : next;
        int start = 0, end = payload.length - 1;
        if (range != null) {
          String[] positions = range.substring(6).split("-", 2);
          start = Integer.parseInt(positions[0]);
          if (positions.length > 1 && !positions[1].isEmpty()) end = Integer.parseInt(positions[1]);
          transfer.rangeRequests.incrementAndGet();
          int active = transfer.active.incrementAndGet();
          activeRange = true;
          transfer.peak.accumulateAndGet(active, Math::max);
          transfer.started.countDown();
        } else transfer.fullRequests.incrementAndGet();
        out.write(
            ("HTTP/1.1 "
                    + (range == null ? "200 OK" : "206 Partial Content")
                    + "\r\nContent-Length: "
                    + (end - start + 1)
                    + "\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "ETag: \"settings-progress-v1\"\r\n"
                    + (range == null
                        ? ""
                        : "Content-Range: bytes "
                            + start
                            + "-"
                            + end
                            + "/"
                            + payload.length
                            + "\r\n")
                    + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        if (range == null) {
          // The engine needs only the header sniff from this probe before opening range workers.
          out.write(payload, 0, 4096);
          out.flush();
          return;
        }
        int copied = 0;
        while (start <= end && !closed) {
          if (!transfer.paced || copied >= 128 * 1024)
            if (!transfer.release.await(15, TimeUnit.SECONDS)) return;
          int count = Math.min(32 * 1024, end - start + 1);
          out.write(payload, start, count);
          out.flush();
          copied += count;
          start += count;
          if (transfer.paced) SystemClock.sleep(copied < 128 * 1024 ? 200 : 70);
        }
      } catch (IOException ignored) {
        // Header probes and a failing assertion's cleanup deliberately disconnect early.
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        if (activeRange) transfer.active.decrementAndGet();
        clients.remove(client);
      }
    }

    @Override
    public void close() throws IOException {
      closed = true;
      first.release.countDown();
      next.release.countDown();
      server.close();
      for (Socket client : clients)
        try {
          client.close();
        } catch (IOException ignored) {
        }
      workers.shutdownNow();
    }
  }
}
