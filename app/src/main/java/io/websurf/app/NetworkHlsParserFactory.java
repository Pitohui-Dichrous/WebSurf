package io.websurf.app;

import android.net.Uri;
import androidx.media3.common.util.UriUtil;
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.ParsingLoadable.Parser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Verify each child against the manifest that actually declared it, after redirects. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class NetworkHlsParserFactory implements HlsPlaylistParserFactory {
  private final HlsPlaylistParserFactory delegate = new DefaultHlsPlaylistParserFactory();

  @Override
  public Parser<HlsPlaylist> createPlaylistParser() {
    return checked(delegate.createPlaylistParser(), Map.of(), null);
  }

  @Override
  public Parser<HlsPlaylist> createPlaylistParser(
      HlsMultivariantPlaylist master, HlsMediaPlaylist previous) {
    return checked(
        delegate.createPlaylistParser(master, previous), master.variableDefinitions, previous);
  }

  private static Parser<HlsPlaylist> checked(
      Parser<HlsPlaylist> parser, Map<String, String> imports, HlsMediaPlaylist previous) {
    return (uri, input) -> {
      long deadline = System.nanoTime() + HlsManifestInput.TIMEOUT_NS;
      String parent = uri.toString();
      byte[] body = HlsManifestInput.read(input, deadline);
      HlsManifestBudget.check(body, parent, imports, previous, deadline);
      HlsPlaylist playlist;
      try {
        playlist = parser.parse(uri, new ByteArrayInputStream(body));
      } catch (RuntimeException malformed) {
        throw new IOException("播放列表格式无效", malformed);
      }
      Children children = new Children(parent, deadline);
      if (playlist instanceof HlsMultivariantPlaylist master) {
        for (Uri child : master.mediaPlaylistUrls) children.check(child.toString());
      } else if (playlist instanceof HlsMediaPlaylist media) {
        for (var segment : media.segments) {
          children.checkSegment(segment);
          for (var part : segment.parts) children.checkSegment(part);
        }
        for (var part : media.trailingParts) children.checkSegment(part);
        for (var report : media.renditionReports.keySet()) children.check(report.toString());
        for (var ad : media.interstitials) {
          if (ad.assetUri != null) children.check(ad.assetUri.toString());
          if (ad.assetListUri != null) children.check(ad.assetListUri.toString());
        }
      }
      HlsManifestInput.checkTime(deadline);
      return playlist;
    };
  }

  private static final class Children {
    private final String parent;
    private final long deadline;
    private final Set<String> checked = new HashSet<>();

    Children(String parent, long deadline) {
      this.parent = parent;
      this.deadline = deadline;
    }

    void checkSegment(HlsMediaPlaylist.SegmentBase segment) throws IOException {
      check(segment.url);
      check(segment.fullSegmentEncryptionKeyUri);
      if (segment.initializationSegment != null) {
        check(segment.initializationSegment.url);
        check(segment.initializationSegment.fullSegmentEncryptionKeyUri);
      }
    }

    void check(String child) throws IOException {
      HlsManifestInput.checkTime(deadline);
      // Segments often share the same long key and initialization URL.
      if (child != null && checked.add(child))
        MediaRequestPolicy.requireNetwork(parent, UriUtil.resolve(parent, child));
    }
  }
}
