package io.websurf.app.download;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import androidx.core.content.FileProvider;
import io.websurf.app.MediaTransferCache;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Downloads locally, then publishes one complete output. No partially written public files. */
final class DownloadEngine {
  interface Progress {
    void changed(DownloadStore.Job job);
  }

  private final Context context;
  private final DownloadStore.Job job;
  private final Progress progress;
  private final File directory;
  private volatile boolean cancelled;
  private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();
  private volatile ExecutorService workers;
  private final int parallelism;
  private long lastUpdate, speedTime, speedBytes;
  private final Map<String, byte[]> keys = new ConcurrentHashMap<>();

  DownloadEngine(Context c, DownloadStore.Job j, Progress p) {
    context = c.getApplicationContext();
    job = j;
    progress = p;
    directory = new File(new File(c.getCacheDir(), "downloads"), j.id);
    parallelism =
        Math.max(
            1,
            Math.min(
                16,
                c.getSharedPreferences("browser", Context.MODE_PRIVATE)
                    .getInt("download_threads", 8)));
  }

  void cancel() {
    cancelled = true;
    for (HttpURLConnection connection : activeConnections) connection.disconnect();
    ExecutorService pool = workers;
    if (pool != null) pool.shutdownNow();
  }

  private void check() {
    if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("已取消");
  }

  void run() throws Exception {
    if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("无法创建下载缓存");
    try {
      HlsPlaylist.resolve(job.url, "");
      MediaTransferCache.beginDownload(context, job.url, job.headers);
      job.state = "downloading";
      job.progress = 0;
      job.bytes = 0;
      job.bytesPerSecond = 0;
      job.completedParts = 0;
      job.totalParts = 0;
      speedTime = android.os.SystemClock.elapsedRealtime();
      speedBytes = 0;
      job.error = "";
      job.detail = "连接中";
      update(true);
      File completed;
      try (Response r = connect(job.url, null)) {
        r.input.mark(8192);
        byte[] head = new byte[4096];
        int length = r.input.read(head);
        r.input.reset();
        String prefix =
            length > 0
                ? new String(head, 0, length, StandardCharsets.UTF_8).replace("\uFEFF", "").trim()
                : "";
        boolean hls = prefix.startsWith("#EXTM3U");
        if (hls) {
          String text = readText(r.input, 8 * 1024 * 1024);
          HlsPlaylist playlist = HlsPlaylist.parse(r.url, text);
          r.close();
          completed = hls(playlist);
          job.mimeType = "video/mp4";
          job.fileName = DownloadNames.mp4(job.title);
        } else {
          if (job.url.toLowerCase(Locale.ROOT).contains(".m3u8")
              || job.mimeType.toLowerCase(Locale.ROOT).contains("mpegurl")) {
            throw new IOException("服务器未返回 m3u8，可能需要重新在网页播放后下载");
          }
          String requestedMime = job.mimeType;
          job.mimeType = DownloadNames.mime(r.connection.getContentType(), job.mimeType);
          if ((requestedMime.startsWith("video/")
                  || requestedMime.startsWith("audio/")
                  || requestedMime.startsWith("image/"))
              && (job.mimeType.equals("text/html") || job.mimeType.equals("application/json"))) {
            throw new IOException("服务器返回了网页或错误信息，请重新在网页播放后下载");
          }
          job.fileName =
              DownloadNames.choose(
                  r.url,
                  r.connection.getHeaderField("Content-Disposition"),
                  job.title,
                  job.mimeType);
          completed = new File(directory, "direct.bin");
          job.total = r.connection.getContentLengthLong();
          Validator validator = Validator.from(r.connection);
          if (parallelism > 1
              && job.total >= 2 * 1024 * 1024L
              && "bytes".equalsIgnoreCase(r.connection.getHeaderField("Accept-Ranges"))
              && validator != null) {
            String resolved = r.url;
            long total = job.total;
            r.close();
            downloadDirectParallel(resolved, total, validator, completed);
          } else {
            try (OutputStream out = new FileOutputStream(completed)) {
              copy(r.input, out, true, job.total);
            }
          }
          if (job.total >= 0 && completed.length() != job.total)
            throw new IOException("文件下载不完整，请重试");
          if (completed.length() == 0) throw new IOException("服务器返回了空文件");
        }
      }
      check();
      job.detail = "保存文件";
      job.bytesPerSecond = 0;
      update(true);
      Uri output = publish(completed);
      try {
        check();
        job.outputUri = output.toString();
        job.progress = 100;
        job.state = "completed";
        job.detail = "已保存";
        progress.changed(job);
      } catch (RuntimeException e) {
        context.getContentResolver().delete(output, null, null);
        job.outputUri = "";
        throw e;
      }
    } catch (IOException e) {
      check();
      throw e;
    } finally {
      stopWorkers();
      job.connections = 0;
      job.bytesPerSecond = 0;
      removeLocal(directory);
    }
  }

  private File hls(HlsPlaylist root) throws Exception {
    List<HlsPlaylist> tracks = new ArrayList<>();
    HlsPlaylist playlist = root;
    String audioUrl = null;
    for (int depth = 0; !playlist.variants.isEmpty(); depth++) {
      if (depth >= 5) throw new IOException("播放列表嵌套过深");
      HlsPlaylist.Variant best = playlist.bestVariant();
      HlsPlaylist.Audio audio = playlist.audioFor(best);
      if (audio != null) audioUrl = audio.url;
      job.detail = "解析 " + best.label();
      update(true);
      playlist = readPlaylist(best.url);
    }
    validateVod(playlist);
    tracks.add(playlist);
    if (audioUrl != null && !audioUrl.equals(playlist.url)) {
      HlsPlaylist audio = readPlaylist(audioUrl);
      for (int depth = 0; !audio.variants.isEmpty(); depth++) {
        if (depth >= 5) throw new IOException("音轨播放列表嵌套过深");
        audio = readPlaylist(audio.bestVariant().url);
      }
      validateVod(audio);
      tracks.add(audio);
    }
    int count = 0;
    for (HlsPlaylist p : tracks) count += p.segments.size();
    job.totalParts = count;
    job.completedParts = 0;
    job.total = -1;
    List<File> media = new ArrayList<>();
    for (int ti = 0; ti < tracks.size(); ti++) {
      HlsPlaylist p = tracks.get(ti);
      HlsPlaylist.Init init = p.segments.isEmpty() ? null : p.segments.get(0).init;
      for (HlsPlaylist.Segment segment : p.segments)
        if (segment.init != init) throw new IOException("播放期间更换了初始化片段，暂不能安全合并");
      File merged = new File(directory, "track-" + ti + ".media");
      try (OutputStream joined = new FileOutputStream(merged)) {
        if (init != null) {
          File header =
              fetchPart(
                  init.url,
                  init.range,
                  init.key,
                  p.segments.get(0).sequence,
                  "track-" + ti + "-init");
          append(header, joined);
          header.delete();
        }
        if (parallelism == 1) {
          for (int i = 0; i < p.segments.size(); i++) {
            File part = fetchSegment(p.segments.get(i), "track-" + ti + "-" + i);
            append(part, joined);
            part.delete();
          }
        } else {
          ExecutorService pool = startWorkers(Math.min(parallelism, p.segments.size()));
          List<Future<File>> pending = new ArrayList<>();
          int submitted = 0;
          try {
            while (submitted < Math.min(parallelism, p.segments.size())) {
              final int index = submitted++;
              final String name = "track-" + ti + "-" + index;
              pending.add(pool.submit(() -> fetchSegment(p.segments.get(index), name)));
            }
            for (int i = 0; i < p.segments.size(); i++) {
              File part = await(pending.remove(0));
              append(part, joined);
              part.delete();
              if (submitted < p.segments.size()) {
                final int index = submitted++;
                final String name = "track-" + ti + "-" + index;
                pending.add(pool.submit(() -> fetchSegment(p.segments.get(index), name)));
              }
            }
          } finally {
            for (Future<File> future : pending) future.cancel(true);
            stopWorkers();
          }
        }
      }
      media.add(merged);
    }
    job.state = "converting";
    job.bytesPerSecond = 0;
    job.progress = 92;
    job.detail = "合并为 MP4";
    update(true);
    File mp4 = new File(directory, "output.mp4");
    remux(media, mp4);
    job.progress = 98;
    update(true);
    return mp4;
  }

  private void validateVod(HlsPlaylist p) throws IOException {
    if (!p.endList) throw new IOException("这是尚未结束的直播，请在直播结束后下载");
    if (p.discontinuity) throw new IOException("此视频含时间轴切换，暂不能安全合并为 MP4");
  }

  private HlsPlaylist readPlaylist(String url) throws Exception {
    try (Response r = connect(url, null)) {
      return HlsPlaylist.parse(r.url, readText(r.input, 8 * 1024 * 1024));
    }
  }

  private File fetchSegment(HlsPlaylist.Segment segment, String name) throws Exception {
    File part = fetchPart(segment.url, segment.range, segment.key, segment.sequence, name);
    synchronized (this) {
      job.completedParts++;
      job.progress = Math.min(90, (int) (90L * job.completedParts / job.totalParts));
      job.detail = "分片 " + job.completedParts + " / " + job.totalParts;
      update(true);
    }
    return part;
  }

  private File fetchPart(
      String url, HlsPlaylist.Range range, HlsPlaylist.Key key, long sequence, String name)
      throws Exception {
    IOException last = null;
    File part = new File(directory, name + ".part");
    for (int attempt = 0; attempt < 3; attempt++) {
      check();
      TransferInput transfer = null;
      String resolvedUrl = url;
      try {
        byte[] keyBytes = key == null ? null : readKey(key.url);
        try (Response r = connect(url, range);
            OutputStream out = new FileOutputStream(part)) {
          resolvedUrl = r.url;
          transfer = new TransferInput(r.input);
          InputStream input = transfer;
          if (keyBytes != null) {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new IvParameterSpec(iv(key.iv, sequence)));
            input = new CipherInputStream(input, cipher);
          }
          long expected = range != null ? range.length : r.connection.getContentLengthLong();
          byte[] buffer = new byte[128 * 1024];
          int n;
          while ((n = input.read(buffer)) != -1) {
            check();
            out.write(buffer, 0, n);
          }
          if (expected >= 0 && transfer.count != expected) throw new IOException("视频分片下载不完整");
          if (part.length() == 0) throw new IOException("视频分片为空");
        }
        return part;
      } catch (IOException e) {
        synchronized (this) {
          job.bytes = Math.max(0, job.bytes - (transfer == null ? 0 : transfer.count));
        }
        MediaTransferCache.invalidate(context, job.url, job.headers, resolvedUrl);
        last = e;
        part.delete();
        check();
      }
    }
    throw last == null ? new IOException("视频分片下载失败") : last;
  }

  private byte[] readKey(String url) throws Exception {
    // Keys are tiny. Serialize only their cache miss, never media transfers.
    synchronized (keys) {
      byte[] bytes = keys.get(url);
      if (bytes != null) return bytes;
      try (Response response = connect(url, null)) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] b = new byte[17];
        int n;
        while ((n = response.input.read(b)) >= 0) {
          check();
          data.write(b, 0, n);
          if (data.size() > 16) throw new IOException("AES 密钥长度无效");
        }
        bytes = data.toByteArray();
      }
      if (bytes.length != 16) throw new IOException("AES 密钥长度无效");
      keys.put(url, bytes);
      return bytes;
    }
  }

  private ExecutorService startWorkers(int count) {
    workers = Executors.newFixedThreadPool(Math.max(1, Math.min(parallelism, count)));
    return workers;
  }

  private void stopWorkers() {
    ExecutorService pool = workers;
    workers = null;
    if (pool == null) return;
    pool.shutdownNow();
    for (HttpURLConnection connection : activeConnections) connection.disconnect();
    boolean interrupted = Thread.interrupted();
    try {
      boolean terminated = false;
      while (!terminated) {
        try {
          terminated = pool.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
        }
        if (!terminated)
          for (HttpURLConnection connection : activeConnections) connection.disconnect();
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private File await(Future<File> future) throws Exception {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException("已取消");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw new IOException("下载分片失败", cause);
    }
  }

  private static final class Validator {
    final String header, value;

    Validator(String header, String value) {
      this.header = header;
      this.value = value;
    }

    static Validator from(HttpURLConnection c) {
      String etag = c.getHeaderField("ETag");
      if (etag != null && etag.startsWith("\"") && etag.endsWith("\""))
        return new Validator("ETag", etag);
      String modified = c.getHeaderField("Last-Modified");
      long last = c.getHeaderFieldDate("Last-Modified", -1),
          date = c.getHeaderFieldDate("Date", -1);
      return modified != null && last > 0 && date - last >= 60000
          ? new Validator("Last-Modified", modified)
          : null;
    }
  }

  private static final class RangeRejectedException extends IOException {
    RangeRejectedException(String message) {
      super(message);
    }

    RangeRejectedException(String message, IOException cause) {
      super(message, cause);
    }
  }

  private void downloadDirectParallel(String url, long total, Validator validator, File output)
      throws Exception {
    List<Future<File>> futures = new ArrayList<>();
    int count = Math.min(parallelism, (int) Math.min(16L, total / (512 * 1024L)));
    ExecutorService pool = startWorkers(count);
    try {
      for (int index = 0; index < count; index++) {
        final long start = (total / count) * index;
        final long end = index + 1 == count ? total : (total / count) * (index + 1);
        final int partIndex = index;
        futures.add(
            pool.submit(
                () -> {
                  File part = new File(directory, "range-" + partIndex + ".part");
                  HlsPlaylist.Range range = new HlsPlaylist.Range(start, end - start);
                  try (Response r = connect(url, range, validator, total);
                      OutputStream out = new FileOutputStream(part)) {
                    long copied = 0;
                    byte[] buffer = new byte[128 * 1024];
                    int n;
                    while (true) {
                      try {
                        n = r.input.read(buffer);
                      } catch (IOException e) {
                        throw new RangeRejectedException("并发连接中断", e);
                      }
                      if (n == -1) break;
                      check();
                      out.write(buffer, 0, n);
                      copied += n;
                      account(n, true);
                      if (copied > range.length) throw new RangeRejectedException("下载范围长度发生变化");
                    }
                    if (copied != range.length) throw new RangeRejectedException("下载范围不完整");
                  }
                  return part;
                }));
      }
      try (OutputStream joined = new FileOutputStream(output)) {
        for (Future<File> future : futures) {
          File part = await(future);
          append(part, joined);
          part.delete();
        }
      }
    } catch (RangeRejectedException failure) {
      for (Future<File> future : futures) future.cancel(true);
      stopWorkers();
      check();
      MediaTransferCache.invalidate(context, job.url, job.headers, url);
      // An ignored range or changed entity must never be mixed with earlier chunks.
      synchronized (this) {
        job.bytes = 0;
        job.progress = 0;
        job.bytesPerSecond = 0;
        speedBytes = 0;
        speedTime = android.os.SystemClock.elapsedRealtime();
        job.detail = "单连接下载";
      }
      try (Response response = connect(job.url, null);
          OutputStream out = new FileOutputStream(output)) {
        job.total = response.connection.getContentLengthLong();
        String mime = DownloadNames.mime(response.connection.getContentType(), job.mimeType);
        if ((job.mimeType.startsWith("video/")
                || job.mimeType.startsWith("audio/")
                || job.mimeType.startsWith("image/"))
            && (mime.equals("text/html") || mime.equals("application/json")))
          throw new IOException("服务器返回了网页或错误信息，请重新在网页播放后下载");
        job.mimeType = mime;
        copy(response.input, out, true, job.total);
      }
    } finally {
      for (Future<File> future : futures) future.cancel(true);
      stopWorkers();
    }
  }

  static byte[] iv(String text, long sequence) throws IOException {
    byte[] result = new byte[16];
    if (text == null || text.isEmpty()) {
      for (int i = 15; i >= 8; i--) {
        result[i] = (byte) sequence;
        sequence >>>= 8;
      }
    } else {
      String value = text.replaceFirst("(?i)^0x", "");
      if (value.length() > 32 || !value.matches("[0-9a-fA-F]+"))
        throw new IOException("AES 初始化向量无效");
      if ((value.length() & 1) != 0) value = "0" + value;
      int start = 16 - value.length() / 2;
      for (int i = 0; i < value.length(); i += 2)
        result[start + i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
    }
    return result;
  }

  private void append(File part, OutputStream out) throws IOException {
    try (InputStream input = new FileInputStream(part)) {
      byte[] buffer = new byte[128 * 1024];
      int n;
      while ((n = input.read(buffer)) != -1) {
        check();
        out.write(buffer, 0, n);
      }
    }
  }

  private void copy(InputStream input, OutputStream out, boolean direct, long total)
      throws IOException {
    byte[] buffer = new byte[128 * 1024];
    int n;
    while ((n = input.read(buffer)) != -1) {
      check();
      out.write(buffer, 0, n);
      account(n, direct);
    }
  }

  private synchronized void account(long count, boolean direct) {
    job.bytes += count;
    if (direct) {
      job.progress = job.total > 0 ? (int) Math.min(96, job.bytes * 96.0 / job.total) : 0;
      job.detail = "下载中";
    }
    update(false);
  }

  private synchronized void update(boolean force) {
    check();
    long now = android.os.SystemClock.elapsedRealtime();
    job.connections = activeConnections.size();
    if (now - speedTime >= 500) {
      job.bytesPerSecond =
          Math.max(0, (job.bytes - speedBytes) * 1000 / Math.max(1, now - speedTime));
      speedTime = now;
      speedBytes = job.bytes;
    }
    if (force || now - lastUpdate >= 600) {
      lastUpdate = now;
      progress.changed(job);
    }
  }

  private Response connect(String address, HlsPlaylist.Range range) throws IOException {
    return connect(address, range, null, -1);
  }

  private Response connect(String address, HlsPlaylist.Range range, Validator validator, long total)
      throws IOException {
    String url = HlsPlaylist.resolve(address, "");
    for (int redirects = 0; redirects < 8; redirects++) {
      check();
      HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
      activeConnections.add(c);
      c.setConnectTimeout(20000);
      c.setReadTimeout(20000);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("Accept-Encoding", "identity");
      boolean capturedCookie = false;
      for (Map.Entry<String, String> h : job.headers.entrySet()) {
        String name = h.getKey();
        if (name.equalsIgnoreCase("Cookie") || name.equalsIgnoreCase("Authorization")) {
          if (!sameOrigin(job.url, url)) continue;
        }
        if (name.equalsIgnoreCase("Host")
            || name.equalsIgnoreCase("Content-Length")
            || name.equalsIgnoreCase("Range")
            || name.equalsIgnoreCase("If-Range")
            || name.equalsIgnoreCase("If-None-Match")
            || name.equalsIgnoreCase("If-Modified-Since")
            || name.equalsIgnoreCase("X-WebSurf-Transfer-Scope")
            || name.equalsIgnoreCase("Accept-Encoding")) continue;
        if (name.contains("\r")
            || name.contains("\n")
            || h.getValue().contains("\r")
            || h.getValue().contains("\n")) continue;
        c.setRequestProperty(name, h.getValue());
        if (name.equalsIgnoreCase("Cookie")) capturedCookie = true;
      }
      try {
        String cookies = CookieManager.getInstance().getCookie(url);
        if (!capturedCookie && cookies != null && !cookies.isEmpty())
          c.setRequestProperty("Cookie", cookies);
      } catch (RuntimeException ignored) {
      }
      if (validator != null) c.setRequestProperty("If-Range", validator.value);
      if (range != null)
        c.setRequestProperty(
            "Range", "bytes=" + range.offset + "-" + (range.offset + range.length - 1));
      int code;
      try {
        code = c.getResponseCode();
      } catch (IOException e) {
        disconnect(c);
        throw e;
      }
      if (code >= 300 && code <= 399) {
        String location = c.getHeaderField("Location");
        disconnect(c);
        if (location == null) throw new IOException("下载重定向缺少地址");
        url = HlsPlaylist.resolve(url, location);
        continue;
      }
      if (code < 200 || code >= 300) {
        disconnect(c);
        String message = "服务器返回 HTTP " + code + "，请回到网页重新播放后重试";
        if (validator != null && (code == 412 || code == 416))
          throw new RangeRejectedException(message);
        throw new IOException(message);
      }
      String encoding = c.getHeaderField("Content-Encoding");
      if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding)) {
        disconnect(c);
        throw new IOException("服务器未返回原始文件数据，请重试");
      }
      if (range != null) {
        String contentRange = c.getHeaderField("Content-Range");
        String prefix = "bytes " + range.offset + "-" + (range.offset + range.length - 1) + "/";
        if (code != 206
            || contentRange == null
            || !contentRange.startsWith(prefix)
            || (validator != null
                && (!contentRange.equals(prefix + total)
                    || !validator.value.equals(c.getHeaderField(validator.header))))) {
          disconnect(c);
          if (validator != null) throw new RangeRejectedException("服务器未正确返回下载范围");
          throw new IOException("服务器未正确返回视频分片范围");
        }
      }
      try {
        return new Response(c, url, range);
      } catch (IOException e) {
        disconnect(c);
        throw e;
      }
    }
    throw new IOException("下载重定向次数过多");
  }

  private void disconnect(HttpURLConnection connection) {
    activeConnections.remove(connection);
    connection.disconnect();
  }

  private static boolean sameOrigin(String a, String b) {
    try {
      URI x = URI.create(a), y = URI.create(b);
      int xp =
          x.getPort() == -1 ? ("https".equalsIgnoreCase(x.getScheme()) ? 443 : 80) : x.getPort();
      int yp =
          y.getPort() == -1 ? ("https".equalsIgnoreCase(y.getScheme()) ? 443 : 80) : y.getPort();
      return x.getScheme().equalsIgnoreCase(y.getScheme())
          && x.getHost().equalsIgnoreCase(y.getHost())
          && xp == yp;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private final class Response implements Closeable {
    final HttpURLConnection connection;
    final BufferedInputStream input;
    final String url;

    Response(HttpURLConnection c, String u, HlsPlaylist.Range range) throws IOException {
      connection = c;
      url = u;
      long entityTotal = -1;
      if (c.getResponseCode() == 200) entityTotal = c.getContentLengthLong();
      else if (c.getResponseCode() == 206) {
        String contentRange = c.getHeaderField("Content-Range");
        if (contentRange != null) {
          int slash = contentRange.lastIndexOf('/');
          if (slash >= 0) {
            try {
              long parsed = Long.parseLong(contentRange.substring(slash + 1).trim());
              if (parsed >= 0) entityTotal = parsed;
            } catch (NumberFormatException ignored) {
            }
          }
        }
      }
      InputStream raw = c.getInputStream();
      raw =
          MediaTransferCache.capture(
              context,
              job.url,
              job.headers,
              u,
              range == null ? 0 : range.offset,
              c.getContentLengthLong(),
              raw,
              entityTotal);
      input = new BufferedInputStream(raw, 64 * 1024);
    }

    public void close() {
      try {
        input.close();
      } catch (IOException ignored) {
      }
      disconnect(connection);
    }
  }

  private final class TransferInput extends FilterInputStream {
    long count;

    TransferInput(InputStream input) {
      super(input);
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value >= 0) {
        count++;
        account(1, false);
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = in.read(buffer, offset, length);
      if (n > 0) {
        count += n;
        account(n, false);
      }
      return n;
    }
  }

  private String readText(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) != -1) {
      check();
      if (out.size() + n > limit) throw new IOException("播放列表过大");
      out.write(buffer, 0, n);
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  private void remux(List<File> sources, File output) throws Exception {
    List<MediaExtractor> extractors = new ArrayList<>();
    List<Map<Integer, Integer>> mappings = new ArrayList<>();
    MediaMuxer muxer = null;
    boolean started = false;
    try {
      muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      boolean hasVideo = false, hasAudio = false;
      for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
        MediaExtractor extractor = new MediaExtractor();
        extractors.add(extractor);
        extractor.setDataSource(sources.get(sourceIndex).getAbsolutePath());
        Map<Integer, Integer> map = new HashMap<>();
        mappings.add(map);
        for (int i = 0; i < extractor.getTrackCount(); i++) {
          MediaFormat format = extractor.getTrackFormat(i);
          String mime = format.getString(MediaFormat.KEY_MIME);
          if (mime == null) continue;
          boolean video = mime.startsWith("video/"), audio = mime.startsWith("audio/");
          if (!video && !audio) continue;
          if (sources.size() > 1 && sourceIndex == 0 && audio) continue;
          if (video && hasVideo || audio && hasAudio) continue;
          if (!(mime.equals("video/avc")
              || mime.equals("video/hevc")
              || mime.equals("video/mp4v-es")
              || mime.equals("audio/mp4a-latm")
              || mime.equals("audio/3gpp")
              || mime.equals("audio/amr-wb"))) {
            throw new IOException("设备无法将此编码无损保存为 MP4：" + mime);
          }
          map.put(i, muxer.addTrack(format));
          extractor.selectTrack(i);
          if (video) hasVideo = true;
          if (audio) hasAudio = true;
        }
      }
      if (!hasVideo && !hasAudio) throw new IOException("没有可导出的音视频轨道");
      muxer.start();
      started = true;
      ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      long samples = 0;
      // All HLS renditions use a common timestamp domain. Keep their initial A/V offset.
      long origin = Long.MAX_VALUE;
      for (MediaExtractor extractor : extractors) {
        long time = extractor.getSampleTime();
        if (time >= 0) origin = Math.min(origin, time);
      }
      if (origin == Long.MAX_VALUE) throw new IOException("视频没有可导出的帧");
      for (int ei = 0; ei < extractors.size(); ei++) {
        MediaExtractor extractor = extractors.get(ei);
        while (true) {
          check();
          int track = extractor.getSampleTrackIndex();
          if (track < 0) break;
          Integer destination = mappings.get(ei).get(track);
          if (destination == null) {
            extractor.advance();
            continue;
          }
          long sampleSize = Build.VERSION.SDK_INT >= 28 ? extractor.getSampleSize() : 0;
          if (sampleSize > buffer.capacity()) {
            if (sampleSize > 64 * 1024 * 1024) throw new IOException("视频帧超过支持的大小");
            buffer = ByteBuffer.allocateDirect((int) sampleSize);
          }
          buffer.clear();
          int size = extractor.readSampleData(buffer, 0);
          if (size < 0) break;
          if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
            throw new IOException("受保护的视频无法导出");
          long time = extractor.getSampleTime();
          info.set(
              0,
              size,
              Math.max(0, time - origin),
              (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                  ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                  : 0);
          muxer.writeSampleData(destination, buffer, info);
          samples++;
          extractor.advance();
        }
      }
      if (samples == 0) throw new IOException("视频没有可导出的帧");
      muxer.stop();
      started = false;
    } finally {
      for (MediaExtractor extractor : extractors) extractor.release();
      if (muxer != null) {
        if (started) {
          try {
            muxer.stop();
          } catch (RuntimeException ignored) {
          }
        }
        muxer.release();
      }
    }
    MediaExtractor verify = new MediaExtractor();
    try {
      verify.setDataSource(output.getAbsolutePath());
      if (verify.getTrackCount() == 0 || output.length() < 32) throw new IOException("MP4 文件校验失败");
    } finally {
      verify.release();
    }
  }

  private Uri publish(File source) throws IOException {
    check();
    if (Build.VERSION.SDK_INT >= 29) {
      ContentValues values = new ContentValues();
      values.put(MediaStore.Downloads.DISPLAY_NAME, job.fileName);
      values.put(MediaStore.Downloads.MIME_TYPE, job.mimeType);
      values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WebSurf");
      values.put(MediaStore.Downloads.IS_PENDING, 1);
      Uri uri =
          context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
      if (uri == null) throw new IOException("无法创建下载文件");
      boolean success = false;
      try {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
          if (out == null) throw new IOException("无法打开下载文件");
          append(source, out);
        }
        check();
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        context.getContentResolver().update(uri, values, null, null);
        success = true;
        return uri;
      } finally {
        if (!success) context.getContentResolver().delete(uri, null, null);
      }
    }
    File folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    if (folder == null || !folder.exists() && !folder.mkdirs()) throw new IOException("下载目录不可用");
    File target = new File(folder, job.fileName);
    for (int n = 1; target.exists(); n++) {
      int dot = job.fileName.lastIndexOf('.');
      target =
          new File(
              folder,
              (dot > 0 ? job.fileName.substring(0, dot) : job.fileName)
                  + " ("
                  + n
                  + ")"
                  + (dot > 0 ? job.fileName.substring(dot) : ""));
    }
    boolean success = false;
    try {
      try (OutputStream out = new FileOutputStream(target)) {
        append(source, out);
      }
      check();
      Uri result = FileProvider.getUriForFile(context, context.getPackageName() + ".files", target);
      success = true;
      return result;
    } finally {
      if (!success) target.delete();
    }
  }

  private void removeLocal(File file) {
    // Only descend through the job's UUID directory underneath the application cache.
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) removeLocal(child);
    }
    file.delete();
  }
}
