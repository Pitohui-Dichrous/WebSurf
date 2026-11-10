package io.websurf.app;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.hls.HlsDataSourceFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Manifest limits apply to the playlist, never to the media bytes it describes. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class HlsManifestInput implements DataSource {
  static final int MAX_BYTES = 2 * 1024 * 1024;
  static final long TIMEOUT_NS = TimeUnit.SECONDS.toNanos(20);

  static final class Request {
    final long deadlineNs = System.nanoTime() + TIMEOUT_NS;
  }

  private final DataSource upstream;

  private HlsManifestInput(DataSource upstream) {
    this.upstream = upstream;
  }

  static HlsDataSourceFactory sources(DataSource.Factory network) {
    return type ->
        type == C.DATA_TYPE_MANIFEST
            ? new HlsManifestInput(network.createDataSource())
            : network.createDataSource();
  }

  @Override
  public long open(DataSpec spec) throws IOException {
    // The network transport enforces this deadline during connection, redirects and blocked reads.
    long length = upstream.open(spec.buildUpon().setCustomData(new Request()).build());
    if (length > MAX_BYTES) throw tooLarge();
    return length;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    return upstream.read(buffer, offset, length);
  }

  @Override
  public Uri getUri() {
    return upstream.getUri();
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return upstream.getResponseHeaders();
  }

  @Override
  public void addTransferListener(TransferListener listener) {
    upstream.addTransferListener(listener);
  }

  @Override
  public void close() throws IOException {
    upstream.close();
  }

  static byte[] read(InputStream stream, long deadlineNs) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    while (true) {
      checkTime(deadlineNs);
      int count = stream.read(buffer, 0, Math.min(buffer.length, MAX_BYTES - bytes.size() + 1));
      checkTime(deadlineNs);
      if (count == -1) return bytes.toByteArray();
      if (bytes.size() + count > MAX_BYTES) throw tooLarge();
      bytes.write(buffer, 0, count);
    }
  }

  static void checkTime(long deadlineNs) throws IOException {
    if (System.nanoTime() - deadlineNs >= 0 || Thread.currentThread().isInterrupted())
      throw new IOException("读取播放列表超时或已取消");
  }

  private static IOException tooLarge() {
    return new IOException("播放列表超过 2 MiB");
  }
}
