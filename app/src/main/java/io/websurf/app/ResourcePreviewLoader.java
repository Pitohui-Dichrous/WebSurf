package io.websurf.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, asynchronous previews. It never uses a remote URL directly in the platform decoder. */
public final class ResourcePreviewLoader implements AutoCloseable {
  public interface Callback {
    void onResult(Result result);
  }

  public static final class Result {
    public Bitmap bitmap;
    public int width, height, maxWidth, maxHeight;
    public long durationMs;
    public String imageSource = "", availableQualities = "", error = "";
    boolean inspectionComplete;
  }

  private static final int CACHE_BYTES = 10 * 1024 * 1024;
  private static final int POSTER_BYTES = 2 * 1024 * 1024;
  private static final int FRAME_BYTES = 6 * 1024 * 1024;
  private static final Pattern RESOLUTION =
      Pattern.compile("(?im)^#EXT-X-STREAM-INF:[^\\r\\n]*\\bRESOLUTION=(\\d+)x(\\d+)");
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          2,
          2,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(24),
          runnable -> {
            Thread thread = new Thread(runnable, "WebSurf-preview");
            thread.setDaemon(true);
            return thread;
          });
  private final LinkedHashMap<String, Result> cache = new LinkedHashMap<>(32, .75f, true);
  private final Map<String, List<Waiting>> pending = new LinkedHashMap<>();
  private int cacheBytes;
  private volatile boolean closed;

  private static final class Waiting {
    final WebResource resource;
    final Callback callback;
    final Map<String, String> requestHeaders, observedHeaders;
    final String identity;

    Waiting(WebResource resource, Map<String, String> headers, String identity, Callback callback) {
      this.resource = resource;
      this.callback = callback;
      requestHeaders = new LinkedHashMap<>(headers);
      observedHeaders = new LinkedHashMap<>(resource.headers);
      this.identity = identity;
    }

    boolean matches() {
      return observedHeaders.equals(resource.headers)
          && identity.equals(key(resource, requestHeaders));
    }
  }

  /**
   * Invoke on the UI thread. Credentials are part of the cache identity, never the visible label.
   */
  public void load(WebResource resource, Map<String, String> headers, Callback callback) {
    if (closed) return;
    String key = key(resource, headers);
    Result known = cache.get(key);
    if (known != null) {
      apply(resource, known);
      callback.onResult(known);
      if (known.inspectionComplete) return;
    }
    Waiting waiting = new Waiting(resource, headers, key, callback);
    List<Waiting> waitingList = pending.get(key);
    if (waitingList != null) {
      waitingList.add(waiting);
      return;
    }
    waitingList = new ArrayList<>();
    waitingList.add(waiting);
    pending.put(key, waitingList);
    String url = resource.url,
        poster = known == null || known.bitmap == null ? resource.posterUrl : "";
    boolean video = resource.isVideo(),
        hls = resource.isHls(),
        picture = "图片".equals(resource.kind);
    Map<String, String> requestHeaders = new LinkedHashMap<>(headers);
    try {
      workers.execute(
          () -> {
            Result result = inspect(url, poster, video, hls, picture, requestHeaders);
            main.post(
                () -> {
                  List<Waiting> receivers = pending.remove(key);
                  if (closed || receivers == null) return;
                  Result seeded = cache.get(key);
                  Result delivered =
                      seeded != null && "播放画面".equals(seeded.imageSource) ? seeded : result;
                  if (delivered == seeded && result.maxWidth > 0) {
                    delivered.maxWidth = result.maxWidth;
                    delivered.maxHeight = result.maxHeight;
                    delivered.availableQualities = result.availableQualities;
                  }
                  delivered.inspectionComplete = result.inspectionComplete;
                  put(key, delivered);
                  for (Waiting receiver : receivers) {
                    if (!receiver.matches()) {
                      Result changed = new Result();
                      changed.error = "资源已更新，请重试";
                      receiver.callback.onResult(changed);
                      continue;
                    }
                    apply(receiver.resource, delivered);
                    receiver.callback.onResult(delivered);
                  }
                });
          });
    } catch (RejectedExecutionException busy) {
      pending.remove(key);
      Result result = new Result();
      result.error = "预览队列繁忙，请重试";
      callback.onResult(result);
    }
  }

  public void retry(WebResource resource, Map<String, String> headers, Callback callback) {
    String key = key(resource, headers);
    Result old = cache.remove(key);
    if (old != null) cacheBytes -= bytes(old);
    load(resource, headers, callback);
  }

  /**
   * Seed from the exact currently rendered native video, after the caller verifies source identity.
   */
  public void seed(
      WebResource resource, Map<String, String> headers, Bitmap bitmap, int width, int height) {
    if (closed || bitmap == null || bitmap.isRecycled()) return;
    String key = key(resource, headers);
    Result existing = cache.get(key), result = new Result();
    result.bitmap = small(bitmap, false);
    result.width = width;
    result.height = height;
    result.imageSource = "播放画面";
    result.inspectionComplete = !resource.isHls();
    if (existing != null) {
      result.maxWidth = existing.maxWidth;
      result.maxHeight = existing.maxHeight;
      result.availableQualities = existing.availableQualities;
      result.durationMs = existing.durationMs;
      result.inspectionComplete |= existing.inspectionComplete;
    }
    put(key, result);
    apply(resource, result, true);
    resource.qualitySource = "播放画面";
    resource.previewRevision++;
  }

  private Result inspect(
      String url,
      String poster,
      boolean video,
      boolean hls,
      boolean picture,
      Map<String, String> headers) {
    Result result = new Result();
    result.inspectionComplete = true;
    if (!WebResource.isHttp(url)) return result;
    try {
      if (picture || WebResource.isHttp(poster)) {
        String imageUrl = picture ? url : poster;
        byte[] encoded =
            readSmall(url, imageUrl, headers, POSTER_BYTES, new Budget(POSTER_BYTES, 10, 9000));
        result.bitmap = decode(encoded);
        if (result.bitmap != null) result.imageSource = picture ? "图片" : "网页封面";
      }
    } catch (Exception ignored) {
    }
    if (closed || Thread.currentThread().isInterrupted()) return result;
    if (hls) {
      try {
        String text =
            new String(
                readSmall(url, url, headers, 256 * 1024, new Budget(256 * 1024, 10, 7000)),
                StandardCharsets.UTF_8);
        applyPlaylist(result, text);
      } catch (Exception failure) {
        result.error = "清晰度暂不可读取";
      }
      // Do not download HLS segments for a scrolling resource list. Native playback supplies
      // frames.
      return result;
    }
    if (!video) return result;
    try (RangeMediaSource source = new RangeMediaSource(url, headers)) {
      MediaMetadataRetriever retriever = new MediaMetadataRetriever();
      try {
        retriever.setDataSource(source);
        result.width =
            positive(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        result.height =
            positive(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        int rotation =
            positive(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        if (rotation == 90 || rotation == 270) {
          int temp = result.width;
          result.width = result.height;
          result.height = temp;
        }
        result.durationMs =
            positiveLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        if (result.bitmap == null && result.width > 0 && result.height > 0) {
          Bitmap frame = null;
          if (Build.VERSION.SDK_INT >= 27)
            frame =
                retriever.getScaledFrameAtTime(
                    0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 216);
          else if ((long) result.width * result.height <= 8_000_000L)
            frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
          if (frame != null) {
            result.bitmap = small(frame, true);
            result.imageSource = "视频画面";
          }
        }
      } finally {
        retriever.release();
      }
    } catch (Exception failure) {
      result.error = "预览暂不可读取";
    }
    return result;
  }

  static void applyPlaylist(Result result, String text) {
    if (!text.replace("\uFEFF", "").trim().startsWith("#EXTM3U")) return;
    Matcher matcher = RESOLUTION.matcher(text);
    TreeSet<Integer> edges = new TreeSet<>();
    while (matcher.find()) {
      int width = positive(matcher.group(1)), height = positive(matcher.group(2));
      if (width <= 0 || height <= 0 || width > 32768 || height > 32768) continue;
      edges.add(Math.min(width, height));
      if ((long) width * height > (long) result.maxWidth * result.maxHeight) {
        result.maxWidth = width;
        result.maxHeight = height;
      }
    }
    List<String> labels = new ArrayList<>();
    for (int edge : edges) labels.add(edge + "p");
    result.availableQualities = String.join(" / ", labels);
  }

  private static int positive(String value) {
    long number = positiveLong(value);
    return number > Integer.MAX_VALUE ? 0 : (int) number;
  }

  private static long positiveLong(String value) {
    try {
      return Math.max(0, Long.parseLong(value));
    } catch (Exception ignored) {
      return 0;
    }
  }

  private static Bitmap decode(byte[] encoded) {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inPreferredConfig = Bitmap.Config.RGB_565;
    int scale = 1;
    while (bounds.outWidth / scale > 768 || bounds.outHeight / scale > 432) scale *= 2;
    options.inSampleSize = scale;
    Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
    return bitmap == null ? null : small(bitmap, true);
  }

  private static Bitmap small(Bitmap original, boolean recycleOriginal) {
    float ratio = Math.min(1f, Math.min(384f / original.getWidth(), 216f / original.getHeight()));
    Bitmap result =
        ratio < 1
            ? Bitmap.createScaledBitmap(
                original,
                Math.max(1, Math.round(original.getWidth() * ratio)),
                Math.max(1, Math.round(original.getHeight() * ratio)),
                true)
            : recycleOriginal ? original : original.copy(Bitmap.Config.ARGB_8888, false);
    if (recycleOriginal && result != original) original.recycle();
    return result;
  }

  private void put(String key, Result result) {
    Result old = cache.put(key, result);
    if (old != null) cacheBytes -= bytes(old);
    cacheBytes += bytes(result);
    while (cache.size() > 80 || cacheBytes > CACHE_BYTES) {
      String first = cache.keySet().iterator().next();
      cacheBytes -= bytes(cache.remove(first));
    }
  }

  private static int bytes(Result result) {
    return result.bitmap == null ? 0 : result.bitmap.getAllocationByteCount();
  }

  private static String key(WebResource resource, Map<String, String> headers) {
    String value =
        resource.url
            + "\n"
            + resource.pageUrl
            + "\n"
            + resource.posterUrl
            + "\n"
            + resource.previewIdentity
            + "\n"
            + new TreeMap<>(headers);
    value += "\n" + CookieManager.getInstance().getCookie(resource.url);
    if (WebResource.isHttp(resource.posterUrl))
      value += "\n" + CookieManager.getInstance().getCookie(resource.posterUrl);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      return result.toString();
    } catch (Exception unavailable) {
      return value;
    }
  }

  private static void apply(WebResource resource, Result result) {
    apply(resource, result, false);
  }

  private static void apply(WebResource resource, Result result, boolean currentRenderedFrame) {
    boolean changed = false;
    if (result.width > 0
        && result.height > 0
        && (currentRenderedFrame || !"播放画面".equals(resource.qualitySource))
        && (resource.width != result.width || resource.height != result.height)) {
      resource.width = result.width;
      resource.height = result.height;
      resource.qualitySource = "播放画面".equals(result.imageSource) ? "播放画面" : "媒体信息";
      changed = true;
    }
    if (result.maxWidth > 0
        && (resource.maxVariantWidth != result.maxWidth
            || resource.maxVariantHeight != result.maxHeight)) {
      resource.maxVariantWidth = result.maxWidth;
      resource.maxVariantHeight = result.maxHeight;
      changed = true;
    }
    if (!result.availableQualities.isEmpty()
        && !result.availableQualities.equals(resource.availableQualities)) {
      resource.availableQualities = result.availableQualities;
      changed = true;
    }
    if (result.durationMs > 0 && resource.durationMs != result.durationMs) {
      resource.durationMs = result.durationMs;
      changed = true;
    }
    if (changed) resource.previewRevision++;
  }

  @Override
  public void close() {
    closed = true;
    pending.clear();
    workers.shutdownNow();
    cache.clear();
    cacheBytes = 0;
  }

  private static final class Budget {
    int remaining, requests;
    final long deadline;

    Budget(int bytes, int requests, long millis) {
      remaining = bytes;
      this.requests = requests;
      deadline = SystemClock.elapsedRealtime() + millis;
    }

    void request() throws IOException {
      if (--requests < 0
          || remaining <= 0
          || Thread.currentThread().isInterrupted()
          || SystemClock.elapsedRealtime() > deadline)
        throw new IOException("Preview budget exceeded");
    }

    void consume(int count) throws IOException {
      remaining -= count;
      if (remaining < 0 || SystemClock.elapsedRealtime() > deadline)
        throw new IOException("Preview budget exceeded");
    }
  }

  /**
   * Shares the browser media credential policy; refuses ignored seeks rather than reading a whole
   * file.
   */
  private static HttpURLConnection connect(
      String original,
      String address,
      Map<String, String> headers,
      long position,
      int length,
      Budget budget)
      throws IOException {
    String url = address;
    for (int redirects = 0; redirects < 8; redirects++) {
      budget.request();
      if (!WebResource.isHttp(url)) throw new IOException("Unsupported preview URL");
      HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(2500);
      connection.setReadTimeout(3000);
      connection.setRequestProperty("Accept-Encoding", "identity");
      for (Map.Entry<String, String> entry :
          ScopedHttpDataSource.scoped(original, url, headers).entrySet())
        connection.setRequestProperty(entry.getKey(), entry.getValue());
      String cookie = CookieManager.getInstance().getCookie(url);
      if (cookie != null && !cookie.isBlank()) connection.setRequestProperty("Cookie", cookie);
      connection.setRequestProperty("Range", "bytes=" + position + "-" + (position + length - 1));
      try {
        int code = connection.getResponseCode();
        if (code >= 300 && code <= 399) {
          String location = connection.getHeaderField("Location");
          if (location == null) throw new IOException("Missing redirect location");
          String next = new URL(new URL(url), location).toString();
          if (url.startsWith("https:") && next.startsWith("http:"))
            throw new IOException("Insecure redirect");
          connection.disconnect();
          url = next;
          continue;
        }
        if (code != 200 && code != 206) throw new IOException("Preview HTTP " + code);
        if (position > 0 && code != 206)
          throw new IOException("Server does not support media ranges");
        if (code == 206) {
          String range = connection.getHeaderField("Content-Range");
          if (range == null || !range.startsWith("bytes " + position + "-"))
            throw new IOException("Invalid media range");
        }
        return connection;
      } catch (IOException | RuntimeException failure) {
        connection.disconnect();
        throw failure;
      }
    }
    throw new IOException("Too many preview redirects");
  }

  private static byte[] readSmall(
      String original, String url, Map<String, String> headers, int max, Budget budget)
      throws IOException {
    HttpURLConnection connection = connect(original, url, headers, 0, max, budget);
    try (InputStream input = connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer, 0, Math.min(buffer.length, max - output.size()))) > 0) {
        budget.consume(count);
        output.write(buffer, 0, count);
        if (output.size() == max) break;
      }
      return output.toByteArray();
    } finally {
      connection.disconnect();
    }
  }

  static final class RangeMediaSource extends MediaDataSource {
    private final String url;
    private final Map<String, String> headers;
    private final Budget budget = new Budget(FRAME_BYTES, 32, 12000);
    private long size = -1;
    private boolean closed;
    private final LinkedHashMap<Long, byte[]> chunks = new LinkedHashMap<>(8, .75f, true);
    private static final int CHUNK = 128 * 1024;

    RangeMediaSource(String url, Map<String, String> headers) {
      this.url = url;
      this.headers = new LinkedHashMap<>(headers);
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int length)
        throws IOException {
      if (closed) throw new IOException("Closed preview");
      if (length == 0) return 0;
      if (position < 0) return -1;
      if (size >= 0 && position >= size) return -1;
      long start = position / CHUNK * CHUNK;
      byte[] bytes = chunks.get(start);
      if (bytes == null) {
        int requestSize = (int) Math.min(CHUNK, size < 0 ? CHUNK : size - start);
        HttpURLConnection connection = connect(url, url, headers, start, requestSize, budget);
        try (InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream()) {
          String range = connection.getHeaderField("Content-Range");
          if (range != null && range.contains("/"))
            size = positiveLong(range.substring(range.lastIndexOf('/') + 1));
          else if (start == 0 && connection.getContentLengthLong() > 0)
            size = connection.getContentLengthLong();
          byte[] scratch = new byte[8192];
          int count;
          while ((count =
                  input.read(scratch, 0, Math.min(scratch.length, requestSize - output.size())))
              > 0) {
            budget.consume(count);
            output.write(scratch, 0, count);
            if (output.size() == requestSize) break;
          }
          bytes = output.toByteArray();
          chunks.put(start, bytes);
          while (chunks.size() > 8) chunks.remove(chunks.keySet().iterator().next());
        } finally {
          connection.disconnect();
        }
      }
      int relative = (int) (position - start), count = Math.min(length, bytes.length - relative);
      if (count <= 0) return -1;
      System.arraycopy(bytes, relative, buffer, offset, count);
      return count;
    }

    @Override
    public synchronized long getSize() throws IOException {
      if (size < 0) {
        byte[] first = new byte[1];
        readAt(0, first, 0, 1);
      }
      return size;
    }

    @Override
    public synchronized void close() {
      closed = true;
      chunks.clear();
    }
  }
}
