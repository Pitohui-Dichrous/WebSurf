package io.websurf.app;

import android.net.Uri;
import androidx.media3.common.util.UriUtil;
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.ParsingLoadable.Parser;
import java.io.IOException;

/** Verify each child against the manifest that actually declared it, after redirects. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class NetworkHlsParserFactory implements HlsPlaylistParserFactory {
  private final HlsPlaylistParserFactory delegate = new DefaultHlsPlaylistParserFactory();

  @Override
  public Parser<HlsPlaylist> createPlaylistParser() {
    return checked(delegate.createPlaylistParser());
  }

  @Override
  public Parser<HlsPlaylist> createPlaylistParser(
      HlsMultivariantPlaylist master, HlsMediaPlaylist previous) {
    return checked(delegate.createPlaylistParser(master, previous));
  }

  private static Parser<HlsPlaylist> checked(Parser<HlsPlaylist> parser) {
    return (uri, input) -> {
      HlsPlaylist playlist = parser.parse(uri, input);
      String parent = uri.toString();
      if (playlist instanceof HlsMultivariantPlaylist master) {
        for (Uri child : master.mediaPlaylistUrls) check(parent, child.toString());
      } else if (playlist instanceof HlsMediaPlaylist media) {
        for (var segment : media.segments) {
          checkSegment(parent, segment);
          for (var part : segment.parts) checkSegment(parent, part);
        }
        for (var part : media.trailingParts) checkSegment(parent, part);
        for (var report : media.renditionReports.keySet()) check(parent, report.toString());
        for (var ad : media.interstitials) {
          if (ad.assetUri != null) check(parent, ad.assetUri.toString());
          if (ad.assetListUri != null) check(parent, ad.assetListUri.toString());
        }
      }
      return playlist;
    };
  }

  private static void checkSegment(String parent, HlsMediaPlaylist.SegmentBase segment)
      throws IOException {
    check(parent, segment.url);
    check(parent, segment.fullSegmentEncryptionKeyUri);
    if (segment.initializationSegment != null) {
      check(parent, segment.initializationSegment.url);
      check(parent, segment.initializationSegment.fullSegmentEncryptionKeyUri);
    }
  }

  private static void check(String parent, String child) throws IOException {
    if (child != null) MediaRequestPolicy.requireNetwork(parent, UriUtil.resolve(parent, child));
  }
}
