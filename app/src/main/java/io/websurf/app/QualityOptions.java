package io.websurf.app;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Choices come exclusively from the current player's supported video tracks. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class QualityOptions {
  private QualityOptions() {}

  public record Choice(
      String label,
      int width,
      int height,
      boolean automatic,
      MediaItem mediaItem,
      TrackGroup group,
      int trackIndex,
      List<Integer> adaptiveIndices) {
    public Choice(
        String label,
        int width,
        int height,
        boolean automatic,
        MediaItem mediaItem,
        TrackGroup group,
        int trackIndex) {
      this(label, width, height, automatic, mediaItem, group, trackIndex, List.of());
    }

    public Choice {
      adaptiveIndices = List.copyOf(adaptiveIndices);
    }

    /** The familiar 360p/720p level is the short edge, including portrait videos. */
    public int resolution() {
      return Math.min(width, height);
    }
  }

  public record Snapshot(List<Choice> choices, String currentLabel, int selectedIndex) {
    public boolean hasMultipleQualities() {
      return choices.stream().filter(c -> !c.automatic()).count() > 1;
    }
  }

  public static String currentLabel(ExoPlayer player) {
    if (player == null) return "未知";
    VideoSize size = player.getVideoSize();
    return size.width > 0 && size.height > 0 ? Math.min(size.width, size.height) + "p" : "未知";
  }

  public static Snapshot snapshot(ExoPlayer player) {
    if (player == null) return new Snapshot(List.of(), "未知", -1);
    return snapshot(
        player.getCurrentTracks(),
        player.getTrackSelectionParameters(),
        player.getCurrentMediaItem(),
        currentLabel(player));
  }

  static Snapshot snapshot(
      Tracks tracks,
      TrackSelectionParameters parameters,
      MediaItem mediaItem,
      String currentLabel) {
    Map<String, Choice> byDimensions = new LinkedHashMap<>();
    Tracks.Group selectedGroup = selectedVideoGroup(tracks);
    for (Tracks.Group group : tracks.getGroups()) {
      if (group != selectedGroup) continue;
      for (int index = 0; index < group.length; index++) {
        if (!group.isTrackSupported(index)) continue;
        Format format = group.getTrackFormat(index);
        if (format.width <= 0 || format.height <= 0) continue;
        String key = format.width + "x" + format.height;
        Choice candidate =
            new Choice(
                Math.min(format.width, format.height)
                    + "p · "
                    + format.width
                    + " × "
                    + format.height,
                format.width,
                format.height,
                false,
                mediaItem,
                group.getMediaTrackGroup(),
                index);
        Choice old = byDimensions.get(key);
        // Prefer an explicitly selected duplicate (codec/bitrate) so the checked row stays honest.
        if (old == null
            || overridden(parameters, candidate)
            || (!overridden(parameters, old) && group.isTrackSelected(index))) {
          byDimensions.put(key, candidate);
        }
      }
    }
    List<Choice> choices = new ArrayList<>(byDimensions.values());
    choices.sort(
        Comparator.comparingInt(Choice::resolution)
            .reversed()
            .thenComparing(
                Comparator.comparingLong((Choice c) -> (long) c.width() * c.height()).reversed()));
    if (choices.size() > 1) choices.add(0, automaticChoice(tracks, mediaItem, currentLabel));
    int selected = -1;
    boolean videoOverride =
        parameters.overrides.keySet().stream().anyMatch(g -> g.type == C.TRACK_TYPE_VIDEO);
    for (int index = 0; index < choices.size(); index++) {
      Choice choice = choices.get(index);
      if ((choice.automatic() && (!videoOverride || adaptiveOverrideMatches(parameters, choice)))
          || overridden(parameters, choice)) selected = index;
    }
    if (selected == -1 && choices.size() == 1) selected = 0;
    return new Snapshot(List.copyOf(choices), currentLabel, selected);
  }

  /** Applies an existing track selection in place. Never changes media, position or play intent. */
  public static boolean apply(ExoPlayer player, Choice choice) {
    if (player == null
        || choice == null
        || !Objects.equals(player.getCurrentMediaItem(), choice.mediaItem())) return false;
    if (choice.automatic()) {
      Tracks.Group current = selectedVideoGroup(player.getCurrentTracks());
      if (choice.group() != null
          && (current == null || !choice.group().equals(current.getMediaTrackGroup())))
        return false;
      choice =
          automaticChoice(
              player.getCurrentTracks(), player.getCurrentMediaItem(), currentLabel(player));
      if (choice == null) return false;
    }
    if (!choice.automatic() && !available(player.getCurrentTracks(), choice)) return false;
    TrackSelectionParameters before = player.getTrackSelectionParameters();
    TrackSelectionParameters after = parametersFor(before, choice);
    if (!before.equals(after)) player.setTrackSelectionParameters(after);
    return true;
  }

  /**
   * Applies a saved short-edge limit once tracks are known. 0 means automatic. Select the highest
   * available level not above the preference, or the lowest supported level when all exceed it.
   * Returns whether parameters changed; repeating it on track callbacks is a no-op.
   */
  public static boolean applyPreferredHeight(ExoPlayer player, int preferredHeight) {
    if (player == null || player.getCurrentMediaItem() == null) return false;
    Snapshot snapshot = snapshot(player);
    Choice chosen;
    if (preferredHeight <= 0) {
      chosen =
          automaticChoice(
              player.getCurrentTracks(), player.getCurrentMediaItem(), snapshot.currentLabel());
      if (chosen == null) return false;
    } else {
      chosen = preferred(snapshot.choices(), preferredHeight);
      if (chosen == null) return false;
    }
    TrackSelectionParameters before = player.getTrackSelectionParameters();
    TrackSelectionParameters after = parametersFor(before, chosen);
    if (before.equals(after)) return false;
    player.setTrackSelectionParameters(after);
    return true;
  }

  static Choice preferred(List<Choice> choices, int preferredHeight) {
    Choice below = null, lowest = null;
    for (Choice choice : choices) {
      if (choice.automatic()) continue;
      if (lowest == null || choice.resolution() < lowest.resolution()) lowest = choice;
      if (choice.resolution() <= preferredHeight
          && (below == null || choice.resolution() > below.resolution())) below = choice;
    }
    return below == null ? lowest : below;
  }

  static TrackSelectionParameters parametersFor(TrackSelectionParameters before, Choice choice) {
    TrackSelectionParameters.Builder builder =
        before.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO);
    if (choice.automatic() && !choice.adaptiveIndices().isEmpty())
      builder.addOverride(new TrackSelectionOverride(choice.group(), choice.adaptiveIndices()));
    else if (!choice.automatic())
      builder.addOverride(new TrackSelectionOverride(choice.group(), choice.trackIndex()));
    return builder.build();
  }

  private static boolean overridden(TrackSelectionParameters parameters, Choice choice) {
    if (choice.automatic() || choice.group() == null) return false;
    TrackSelectionOverride override = parameters.overrides.get(choice.group());
    return override != null
        && override.trackIndices.size() == 1
        && override.trackIndices.get(0) == choice.trackIndex();
  }

  private static boolean available(Tracks tracks, Choice choice) {
    Tracks.Group current = selectedVideoGroup(tracks);
    if (current == null || !current.getMediaTrackGroup().equals(choice.group())) return false;
    for (Tracks.Group group : tracks.getGroups())
      if (group.getType() == C.TRACK_TYPE_VIDEO
          && group.getMediaTrackGroup().equals(choice.group())
          && choice.trackIndex() >= 0
          && choice.trackIndex() < group.length
          && group.isTrackSupported(choice.trackIndex())) return true;
    return false;
  }

  private static boolean adaptiveOverrideMatches(
      TrackSelectionParameters parameters, Choice choice) {
    if (choice.group() == null || choice.adaptiveIndices().isEmpty()) return false;
    TrackSelectionOverride override = parameters.overrides.get(choice.group());
    return override != null && override.trackIndices.equals(choice.adaptiveIndices());
  }

  private static boolean hasSupportedVideo(Tracks.Group group) {
    if (group.getType() != C.TRACK_TYPE_VIDEO) return false;
    for (int i = 0; i < group.length; i++) {
      Format format = group.getTrackFormat(i);
      if (group.isTrackSupported(i) && format.width > 0 && format.height > 0) return true;
    }
    return false;
  }

  private static Tracks.Group selectedVideoGroup(Tracks tracks) {
    Tracks.Group only = null;
    int supportedGroups = 0;
    for (Tracks.Group group : tracks.getGroups()) {
      if (!hasSupportedVideo(group)) continue;
      if (group.isSelected()) return group;
      only = group;
      supportedGroups++;
    }
    // Before renderer selection, multiple groups may be different camera angles, not qualities.
    return supportedGroups == 1 ? only : null;
  }

  private static Choice automaticChoice(Tracks tracks, MediaItem item, String label) {
    Tracks.Group current = selectedVideoGroup(tracks);
    long groups = tracks.getGroups().stream().filter(QualityOptions::hasSupportedVideo).count();
    if (groups > 1) {
      if (current == null) return null;
      List<Integer> indices = new ArrayList<>();
      for (int index = 0; index < current.length; index++)
        if (current.isTrackSupported(index)) indices.add(index);
      // Keep adaptive selection within the current camera angle/role instead of clearing its group.
      return new Choice(
          "自动 · 当前 " + label, 0, 0, true, item, current.getMediaTrackGroup(), -1, indices);
    }
    return new Choice("自动 · 当前 " + label, 0, 0, true, item, null, -1);
  }
}
