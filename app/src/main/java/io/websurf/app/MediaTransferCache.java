package io.websurf.app;

import android.content.Context;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Download bytes become readable cache spans independently of playback time. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MediaTransferCache {
  public static final String SCOPE_HEADER = "X-WebSurf-Transfer-Scope";
  private static final String PROCESS = UUID.randomUUID().toString();
  private static SimpleCache instance;
  private static final java.util.concurrent.ConcurrentHashMap<
          String, java.util.concurrent.atomic.AtomicLong>
      generations = new java.util.concurrent.ConcurrentHashMap<>();

  private static final java.util.concurrent.ConcurrentHashMap<
          String, java.util.concurrent.atomic.AtomicLong>
      downloads = new java.util.concurrent.ConcurrentHashMap<>();

  private MediaTransferCache() {}

  public static void beginDownload(Context context, String source, Map<String, String> headers) {
    downloads
        .computeIfAbsent(
            baseKey(source, headers, ""), k -> new java.util.concurrent.atomic.AtomicLong())
        .incrementAndGet();
  }

  private static String currentBase(String source, Map<String, String> headers, String uri) {
    long download =
        downloads
            .computeIfAbsent(
                baseKey(source, headers, ""), k -> new java.util.concurrent.atomic.AtomicLong())
            .get();
    return baseKey(source, headers, uri) + "." + download;
  }

  private static synchronized SimpleCache cache(Context context) {
    if (instance == null)
      instance =
          new SimpleCache(
              new File(context.getCacheDir(), "media-transfer"),
              new LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
              new StandaloneDatabaseProvider(context.getApplicationContext()));
    return instance;
  }

  private static String baseKey(String source, Map<String, String> headers, String uri) {
    TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    sorted.putAll(headers);
    StringBuilder value =
        new StringBuilder(PROCESS).append('\n').append(source).append('\n').append(uri);
    sorted.forEach(
        (name, content) ->
            value
                .append('\n')
                .append(name.toLowerCase(java.util.Locale.ROOT))
                .append(':')
                .append(content));
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256")
              .digest(value.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      return result.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static String key(String source, Map<String, String> headers, String uri) {
    String base = currentBase(source, headers, uri);
    return base
        + "."
        + generations
            .computeIfAbsent(base, k -> new java.util.concurrent.atomic.AtomicLong())
            .get();
  }

  /** Playback reads downloaded spans and uses its secure HTTP transport for remaining holes. */
  public static DataSource.Factory playback(
      Context context, String source, Map<String, String> headers, DataSource.Factory upstream) {
    try {
      return new CacheDataSource.Factory()
          .setCache(cache(context))
          .setCacheKeyFactory(spec -> key(source, headers, spec.uri.toString()))
          .setUpstreamDataSourceFactory(upstream)
          .setCacheWriteDataSinkFactory(null)
          .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    } catch (RuntimeException cacheUnavailable) {
      return upstream;
    }
  }

  /** Called only after the downloader has validated status, entity and requested byte range. */
  public static InputStream capture(
      Context context,
      String source,
      Map<String, String> headers,
      String uri,
      long offset,
      long length,
      InputStream network) {
    return capture(
        context, source, headers, uri, offset, length, network, offset == 0 ? length : -1);
  }

  public static InputStream capture(
      Context context,
      String source,
      Map<String, String> headers,
      String uri,
      long offset,
      long length,
      InputStream network,
      long resourceLength) {
    if (length <= 0 || offset < 0 || !ScopedHttpDataSource.sameOrigin(source, uri)) return network;
    SimpleCache store;
    CacheSpan span = null;
    String cacheKey = key(source, headers, uri);
    try {
      store = cache(context);
      span = store.startReadWriteNonBlocking(cacheKey, offset, length);
      // Cache is only an optimization. Never block a download on a player or another worker.
      if (span == null || span.isCached) return network;
      if (!span.isOpenEnded() && span.length < length) {
        store.releaseHoleSpan(span);
        return network;
      }
      CacheDataSink sink = new CacheDataSink(store, 256 * 1024);
      sink.open(
          new DataSpec.Builder()
              .setUri(uri)
              .setKey(cacheKey)
              .setPosition(offset)
              .setLength(length)
              .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
              .build());
      return new CapturingInput(network, store, span, sink, cacheKey, length, resourceLength);
    } catch (IOException | RuntimeException unavailable) {
      if (span != null && !span.isCached) {
        try {
          cache(context).releaseHoleSpan(span);
        } catch (RuntimeException ignored) {
        }
      }
      return network;
    }
  }

  public static void invalidate(
      Context context, String source, Map<String, String> headers, String uri) {
    String base = currentBase(source, headers, uri);
    long old =
        generations
            .computeIfAbsent(base, k -> new java.util.concurrent.atomic.AtomicLong())
            .getAndIncrement();
    try {
      cache(context).removeResource(base + "." + old);
    } catch (RuntimeException ignored) {
    }
  }

  private static final class CapturingInput extends FilterInputStream {
    private final SimpleCache cache;
    private CacheSpan span;
    private CacheDataSink sink;
    private boolean closed;
    private final String key;
    private final long expected, total;
    private long received;

    CapturingInput(
        InputStream input,
        SimpleCache cache,
        CacheSpan span,
        CacheDataSink sink,
        String key,
        long expected,
        long total) {
      super(input);
      this.cache = cache;
      this.span = span;
      this.sink = sink;
      this.key = key;
      this.expected = expected;
      this.total = total;
    }

    private void release() {
      if (sink != null && received == expected && total > 0) {
        try {
          ContentMetadataMutations metadata = new ContentMetadataMutations();
          ContentMetadataMutations.setContentLength(metadata, total);
          cache.applyContentMetadataMutations(key, metadata);
        } catch (IOException | RuntimeException ignored) {
        }
      }
      if (sink != null) {
        try {
          sink.close();
        } catch (IOException | RuntimeException ignored) {
        }
        sink = null;
      }
      if (span != null) {
        try {
          cache.releaseHoleSpan(span);
        } catch (RuntimeException ignored) {
        }
        span = null;
      }
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int n = in.read(bytes, offset, length);
      if (n > 0) received += n;
      if (n < 0) release();
      else if (n > 0 && sink != null) {
        try {
          sink.write(bytes, offset, n);
        } catch (IOException | RuntimeException cacheError) {
          release();
        }
      }
      return n;
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      try {
        in.close();
      } finally {
        release();
      }
    }
  }
}
