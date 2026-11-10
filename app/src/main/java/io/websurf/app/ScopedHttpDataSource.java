package io.websurf.app;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.*;
import java.net.*;
import java.util.*;

/** Applies browser credentials to each media URI, including every redirect and HLS segment. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class ScopedHttpDataSource extends BaseDataSource {
  static final class Factory implements DataSource.Factory {
    private final String source;
    private final Map<String, String> headers;

    Factory(String source, Map<String, String> headers) {
      this.source = source;
      this.headers = new LinkedHashMap<>(headers);
    }

    @Override
    public DataSource createDataSource() {
      return new ScopedHttpDataSource(source, headers);
    }
  }

  private final String source;
  private final Map<String, String> headers;
  private HttpURLConnection connection;
  private InputStream input;
  private Uri uri;
  private long remaining = C.LENGTH_UNSET;
  private boolean opened;

  ScopedHttpDataSource(String source, Map<String, String> headers) {
    super(true);
    this.source = source;
    this.headers = headers;
  }

  static boolean sameOrigin(String a, String b) {
    return MediaRequestPolicy.sameOrigin(a, b);
  }

  @Override
  public long open(DataSpec spec) throws IOException {
    transferInitializing(spec);
    String url = spec.uri.toString();
    try {
      MediaRequestPolicy.requireNetwork(source, url);
      Map<String, String> scopedHeaders = MediaRequestPolicy.headers(source, url, headers);
      for (int redirects = 0; redirects <= 8; redirects++) {
        connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        for (Map.Entry<String, String> e : scopedHeaders.entrySet())
          connection.setRequestProperty(e.getKey(), e.getValue());
        if (spec.position > 0 || spec.length != C.LENGTH_UNSET)
          connection.setRequestProperty(
              "Range",
              "bytes="
                  + spec.position
                  + "-"
                  + (spec.length == C.LENGTH_UNSET ? "" : spec.position + spec.length - 1));
        int code = connection.getResponseCode();
        if (code >= 300 && code <= 399) {
          String location = connection.getHeaderField("Location");
          if (location == null) throw new IOException("媒体重定向缺少地址");
          String next = new URL(new URL(url), location).toString();
          connection.disconnect();
          connection = null;
          MediaRequestPolicy.requireNetwork(url, next);
          scopedHeaders = MediaRequestPolicy.headers(url, next, scopedHeaders);
          url = next;
          continue;
        }
        if (code < 200 || code >= 300) throw new IOException("媒体请求失败 HTTP " + code);
        uri = Uri.parse(url);
        input = connection.getInputStream();
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
        long length = connection.getContentLengthLong();
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
      close();
      throw e;
    }
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
    return connection == null ? Collections.emptyMap() : connection.getHeaderFields();
  }

  @Override
  public void close() throws IOException {
    try {
      if (input != null) input.close();
    } finally {
      input = null;
      if (connection != null) connection.disconnect();
      connection = null;
      uri = null;
      if (opened) {
        opened = false;
        transferEnded();
      }
    }
  }
}
