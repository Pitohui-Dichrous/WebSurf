package io.websurf.app;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Response;

/** Applies browser credentials to each media URI, including every redirect and HLS segment. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class ScopedHttpDataSource extends BaseDataSource {
  private static final Pattern CONTENT_RANGE =
      Pattern.compile("bytes ([0-9]+)-([0-9]+)/(\\*|[0-9]+)", Pattern.CASE_INSENSITIVE);

  static final class Factory implements DataSource.Factory {
    private final String source;
    private final Map<String, String> headers;
    private final boolean hls;

    Factory(String source, Map<String, String> headers, boolean hls) {
      this.source = source;
      this.headers = new LinkedHashMap<>(headers);
      this.hls = hls;
    }

    @Override
    public DataSource createDataSource() {
      return new ScopedHttpDataSource(source, headers, hls);
    }
  }

  private final String source;
  private final Map<String, String> headers;
  private final boolean hls;
  private Response connection;
  private InputStream input;
  private Uri uri;
  private long remaining = C.LENGTH_UNSET;
  private boolean opened;

  private ScopedHttpDataSource(String source, Map<String, String> headers, boolean hls) {
    super(true);
    this.source = source;
    this.headers = headers;
    this.hls = hls;
  }

  static boolean sameOrigin(String a, String b) {
    return MediaRequestPolicy.sameOrigin(a, b);
  }

  @Override
  public long open(DataSpec spec) throws IOException {
    transferInitializing(spec);
    String url = spec.uri.toString();
    Call call = null;
    try {
      MediaRequestPolicy.requireNetwork(source, url);
      Map<String, String> scopedHeaders = MediaRequestPolicy.headers(source, url, headers);
      String range = null;
      if (spec.length != C.LENGTH_UNSET) {
        if (spec.position > Long.MAX_VALUE - (spec.length - 1))
          throw new IOException("媒体请求范围溢出");
        range = "bytes=" + spec.position + "-" + (spec.position + (spec.length - 1));
      } else if (spec.position > 0) range = "bytes=" + spec.position + "-";
      for (int redirects = 0; redirects <= 8; redirects++) {
        call =
            NativeMediaHttp.get(
                source,
                url,
                headers.get(NativeMediaHttp.LOCAL_ORIGIN),
                scopedHeaders,
                range,
                null,
                15000,
                20000);
        if (spec.customData instanceof HlsManifestInput.Request request)
          call.timeout().deadlineNanoTime(request.deadlineNs);
        connection = call.execute();
        int code = connection.code();
        if (code >= 300 && code <= 399) {
          String location = connection.header("Location");
          if (location == null) throw new IOException("媒体重定向缺少地址");
          String next = new URL(new URL(url), location).toString();
          connection.close();
          connection = null;
          MediaRequestPolicy.requireNetwork(url, next);
          scopedHeaders = MediaRequestPolicy.headers(url, next, scopedHeaders);
          url = next;
          continue;
        }
        if (code < 200 || code >= 300) throw new IOException("媒体请求失败 HTTP " + code);
        long length = connection.body().contentLength();
        if (hls && range != null) {
          if (code == 206) length = hlsRangeLength(spec, connection, length);
          else if (code != 200 || spec.position != 0)
            throw new IOException("服务器未返回请求的 HLS 字节范围");
          // A full response can supply a range starting at zero without discarding a prefix.
        }
        uri = Uri.parse(url);
        input = connection.body().byteStream();
        long skipped = code == 200 ? spec.position : 0;
        if (skipped > 0) {
          byte[] buffer = new byte[8192];
          long todo = skipped;
          while (todo > 0) {
            int n = input.read(buffer, 0, (int) Math.min(todo, buffer.length));
            if (n < 0) throw new EOFException("媒体定位超出文件末尾");
            todo -= n;
          }
        }
        remaining =
            spec.length != C.LENGTH_UNSET
                ? spec.length
                : length >= 0 ? Math.max(0, length - skipped) : C.LENGTH_UNSET;
        opened = true;
        transferStarted(spec);
        return remaining;
      }
      throw new IOException("媒体重定向过多");
    } catch (IOException | RuntimeException e) {
      if (call != null) call.cancel();
      close();
      throw e;
    }
  }

  private static long hlsRangeLength(DataSpec spec, Response response, long contentLength)
      throws IOException {
    Matcher range = CONTENT_RANGE.matcher(response.header("Content-Range", ""));
    if (range.matches()) {
      try {
        long start = Long.parseLong(range.group(1)), end = Long.parseLong(range.group(2));
        long length = Math.addExact(end - start, 1);
        if (start == spec.position
            && length > 0
            && ("*".equals(range.group(3)) || end < Long.parseLong(range.group(3)))
            && (spec.length == C.LENGTH_UNSET || length == spec.length)
            && (contentLength == C.LENGTH_UNSET || contentLength == length)) return length;
      } catch (NumberFormatException | ArithmeticException ignored) {
        // The endpoints must describe a representable byte count.
      }
    }
    throw new IOException("服务器返回的 HLS 字节范围无效");
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    if (length == 0) return 0;
    if (remaining == 0) return C.RESULT_END_OF_INPUT;
    int count =
        input.read(
            bytes,
            offset,
            remaining == C.LENGTH_UNSET ? length : (int) Math.min(length, remaining));
    if (count < 0) {
      if (remaining > 0) throw new EOFException("媒体连接提前结束");
      return C.RESULT_END_OF_INPUT;
    }
    if (remaining != C.LENGTH_UNSET) remaining -= count;
    bytesTransferred(count);
    return count;
  }

  @Override
  public Uri getUri() {
    return uri;
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return connection == null ? Collections.emptyMap() : connection.headers().toMultimap();
  }

  @Override
  public void close() throws IOException {
    try {
      if (input != null) input.close();
    } finally {
      input = null;
      if (connection != null) connection.close();
      connection = null;
      uri = null;
      if (opened) {
        opened = false;
        transferEnded();
      }
    }
  }
}
