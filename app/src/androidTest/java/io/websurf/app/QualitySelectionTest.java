package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Supported-track filtering and in-place HLS selection against the existing local fixtures. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class QualitySelectionTest {
  private ActivityScenario<MainActivity> scenario;
  private ExoPlayer player;
  private PlayerView view;

  @Test
  public void supportedTracksAreDeduplicatedAndVideoChoicePreservesAudioOverrides() {
    Format low = format(640, 360), high = format(1280, 720), unsupported = format(1920, 1080);
    TrackGroup video =
        new TrackGroup(
            "video", low, high, high.buildUpon().setAverageBitrate(2000000).build(), unsupported);
    TrackGroup audio =
        new TrackGroup(
            "audio", new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build());
    Tracks tracks =
        new Tracks(
            List.of(
                new Tracks.Group(
                    video,
                    true,
                    new int[] {
                      C.FORMAT_HANDLED,
                      C.FORMAT_HANDLED,
                      C.FORMAT_HANDLED,
                      C.FORMAT_EXCEEDS_CAPABILITIES
                    },
                    new boolean[] {false, false, true, false}),
                new Tracks.Group(
                    audio, false, new int[] {C.FORMAT_HANDLED}, new boolean[] {true})));
    TrackSelectionOverride audioOverride = new TrackSelectionOverride(audio, 0);
    TrackSelectionParameters parameters =
        new TrackSelectionParameters.Builder(ApplicationProvider.getApplicationContext())
            .setPreferredAudioLanguage("ja")
            .addOverride(audioOverride)
            .build();
    QualityOptions.Snapshot snapshot =
        QualityOptions.snapshot(
            tracks, parameters, MediaItem.fromUri("https://example.test/main.m3u8"), "720p");
    assertEquals(3, snapshot.choices().size());
    assertEquals(0, snapshot.selectedIndex());
    assertTrue(snapshot.choices().get(0).automatic());
    QualityOptions.Choice selected = snapshot.choices().get(1);
    assertEquals(720, selected.resolution());
    assertEquals("720p · 1280 × 720", selected.label());
    assertEquals(2, selected.trackIndex());
    TrackSelectionParameters fixed = QualityOptions.parametersFor(parameters, selected);
    assertEquals(audioOverride, fixed.overrides.get(audio));
    assertEquals(parameters.preferredAudioLanguages, fixed.preferredAudioLanguages);
    assertEquals(List.of(2), fixed.overrides.get(video).trackIndices);
    TrackSelectionParameters automatic =
        QualityOptions.parametersFor(fixed, snapshot.choices().get(0));
    assertFalse(automatic.overrides.containsKey(video));
    assertEquals(audioOverride, automatic.overrides.get(audio));
    assertEquals(
        1, QualityOptions.snapshot(tracks, fixed, selected.mediaItem(), "720p").selectedIndex());
  }

  @Test
  public void singlePortraitTrackIsReportedAsOneActualQuality() {
    TrackGroup video = new TrackGroup("portrait", format(720, 1280));
    Tracks tracks =
        new Tracks(
            List.of(
                new Tracks.Group(
                    video, false, new int[] {C.FORMAT_HANDLED}, new boolean[] {true})));
    TrackSelectionParameters parameters =
        new TrackSelectionParameters.Builder(ApplicationProvider.getApplicationContext()).build();
    QualityOptions.Snapshot snapshot =
        QualityOptions.snapshot(
            tracks, parameters, MediaItem.fromUri("https://example.test/portrait.mp4"), "720p");
    assertFalse(snapshot.hasMultipleQualities());
    assertEquals(1, snapshot.choices().size());
    assertFalse(snapshot.choices().get(0).automatic());
    assertEquals("720p · 720 × 1280", snapshot.choices().get(0).label());
  }

  @Test
  public void otherCameraAnglesAreNotPresentedAsQualityAndAutoStaysInCurrentGroup() {
    TrackGroup front = new TrackGroup("front", format(640, 360), format(1280, 720));
    TrackGroup back = new TrackGroup("back", format(1920, 1080), format(3840, 2160));
    Tracks tracks =
        new Tracks(
            List.of(
                new Tracks.Group(
                    front,
                    true,
                    new int[] {C.FORMAT_HANDLED, C.FORMAT_HANDLED},
                    new boolean[] {true, false}),
                new Tracks.Group(
                    back,
                    true,
                    new int[] {C.FORMAT_HANDLED, C.FORMAT_HANDLED},
                    new boolean[] {false, false})));
    TrackSelectionParameters parameters =
        new TrackSelectionParameters.Builder(ApplicationProvider.getApplicationContext()).build();
    MediaItem item = MediaItem.fromUri("https://example.test/cameras.m3u8");
    QualityOptions.Snapshot snapshot = QualityOptions.snapshot(tracks, parameters, item, "360p");
    assertEquals(3, snapshot.choices().size());
    assertTrue(snapshot.choices().stream().noneMatch(c -> c.resolution() > 720));
    QualityOptions.Choice automatic = snapshot.choices().get(0);
    assertTrue(automatic.automatic());
    TrackSelectionParameters applied = QualityOptions.parametersFor(parameters, automatic);
    assertEquals(List.of(0, 1), applied.overrides.get(front).trackIndices);
    assertFalse(applied.overrides.containsKey(back));
    assertEquals(0, QualityOptions.snapshot(tracks, applied, item, "360p").selectedIndex());
    Tracks undecided =
        new Tracks(
            List.of(
                new Tracks.Group(
                    front,
                    true,
                    new int[] {C.FORMAT_HANDLED, C.FORMAT_HANDLED},
                    new boolean[] {false, false}),
                new Tracks.Group(
                    back,
                    true,
                    new int[] {C.FORMAT_HANDLED, C.FORMAT_HANDLED},
                    new boolean[] {false, false})));
    assertTrue(QualityOptions.snapshot(undecided, parameters, item, "未知").choices().isEmpty());
  }

  @Test
  public void hlsSelectionRetainsPlayerSurfacePositionAndPauseIntent() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    AtomicInteger transitions = new AtomicInteger();
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse("http://127.0.0.1:8765/?quality-native=" + System.nanoTime())));
    try {
      ui(
          a -> {
            player = new ExoPlayer.Builder(a).build();
            view =
                (PlayerView) LayoutInflater.from(a).inflate(R.layout.native_video, a.root, false);
            a.root.addView(view, new FrameLayout.LayoutParams(-1, 480));
            view.setPlayer(player);
            player.addListener(
                new Player.Listener() {
                  @Override
                  public void onMediaItemTransition(MediaItem item, int reason) {
                    transitions.incrementAndGet();
                  }
                });
            player.setMediaItem(
                new MediaItem.Builder()
                    .setUri("http://127.0.0.1:8765/public/hls/master.m3u8")
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
                2500);
            player.prepare();
            player.setPlayWhenReady(false);
            return null;
          });
      await(
          "HLS qualities did not load",
          12000,
          () ->
              ui(
                  a ->
                      player.getPlayerError() == null
                          && player.getPlaybackState() == Player.STATE_READY
                          && QualityOptions.snapshot(player).hasMultipleQualities()));
      ExoPlayer samePlayer = player;
      View sameSurface = ui(a -> view.getVideoSurfaceView());
      MediaItem sameItem = ui(a -> player.getCurrentMediaItem());
      int transitionCount = transitions.get();
      QualityOptions.Choice high =
          ui(
              a ->
                  QualityOptions.snapshot(player).choices().stream()
                      .filter(c -> c.resolution() == 720)
                      .findFirst()
                      .orElseThrow());
      QualityOptions.Choice low =
          ui(
              a ->
                  QualityOptions.snapshot(player).choices().stream()
                      .filter(c -> c.resolution() == 360)
                      .findFirst()
                      .orElseThrow());
      for (QualityOptions.Choice choice : List.of(low, high)) {
        ui(
            a -> {
              long before = player.getCurrentPosition();
              assertTrue(QualityOptions.apply(player, choice));
              assertEquals(before, player.getCurrentPosition(), 50);
              assertFalse(player.getPlayWhenReady());
              assertSame(samePlayer, player);
              assertSame(sameSurface, view.getVideoSurfaceView());
              assertEquals(sameItem, player.getCurrentMediaItem());
              return null;
            });
        await(
            "Track override was not selected",
            5000,
            () ->
                ui(
                    a ->
                        player.getCurrentTracks().getGroups().stream()
                            .anyMatch(
                                g ->
                                    g.getMediaTrackGroup().equals(choice.group())
                                        && g.isTrackSelected(choice.trackIndex()))));
      }
      assertEquals(transitionCount, transitions.get());
      ui(
          a -> {
            player.play();
            return null;
          });
      await(
          "Selected 720p did not render",
          10000,
          () -> ui(a -> player.isPlaying() && player.getVideoSize().height == 720));
      ui(
          a -> {
            player.pause();
            assertTrue(QualityOptions.applyPreferredHeight(player, 480));
            assertFalse(
                "Repeating a preference must not rebuild selection",
                QualityOptions.applyPreferredHeight(player, 480));
            assertTrue(QualityOptions.applyPreferredHeight(player, 0));
            assertFalse(QualityOptions.applyPreferredHeight(player, 0));
            assertFalse(
                player.getTrackSelectionParameters().overrides.keySet().stream()
                    .anyMatch(g -> g.type == C.TRACK_TYPE_VIDEO));
            player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8765/public/sample-360.mp4"));
            assertFalse(
                "Old page's quality menu must not select a new media item",
                QualityOptions.apply(player, high));
            return null;
          });
    } finally {
      if (scenario != null) {
        ui(
            a -> {
              if (view != null) {
                view.setPlayer(null);
                a.root.removeView(view);
              }
              if (player != null) player.release();
              return null;
            });
        scenario.close();
      }
    }
  }

  private static Format format(int width, int height) {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(width)
        .setHeight(height)
        .build();
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> value = new AtomicReference<>();
    scenario.onActivity(a -> value.set(action.apply(a)));
    return value.get();
  }

  private interface Ready {
    boolean value() throws Exception;
  }

  private static void await(String message, long timeout, Ready condition) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeout;
    do {
      if (condition.value()) return;
      SystemClock.sleep(50);
    } while (SystemClock.elapsedRealtime() < end);
    fail(message);
  }
}
