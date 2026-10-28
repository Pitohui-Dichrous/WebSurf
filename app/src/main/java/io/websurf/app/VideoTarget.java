package io.websurf.app;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** A snapshot of one DOM video, scoped to its document and source generation. */
final class VideoTarget {
  String documentId, mediaId, url, page, title;
  long sourceVersion;
  double time, duration, rate, volume;
  boolean paused, ended, muted, userInitiated, suppressed;
  int width, height;
  float viewportWidth, viewportHeight;
  RectF rect, clip, contentClip;
  float topScrollX, topScrollY, visualOffsetX, visualOffsetY;
  boolean nativeScrollFollow = true;
  long layoutRevision;
  String poster = "";
  boolean nativeFailed;
  List<QualitySource> qualitySources = List.of();

  record QualitySource(String url, String mime, String label, int width, int height) {
    int resolution() {
      return width > 0 ? Math.min(width, height) : height;
    }
  }

  String key() {
    return documentId + ":" + mediaId;
  }

  static VideoTarget read(JSONObject e, VideoTarget previous) {
    VideoTarget t = new VideoTarget();
    t.documentId = e.optString("documentId");
    t.mediaId = e.optString("mediaId");
    t.sourceVersion = e.optLong("sourceVersion", 0);
    t.url = e.optString("url", previous == null ? "" : previous.url);
    t.page = e.optString("page", previous == null ? "" : previous.page);
    t.title = e.optString("title", previous == null ? "网页视频" : previous.title);
    t.time = e.optDouble("time", previous == null ? 0 : previous.time);
    t.duration = e.optDouble("duration", previous == null ? 0 : previous.duration);
    t.rate = e.optDouble("rate", previous == null ? 1 : previous.rate);
    t.volume = e.optDouble("volume", previous == null ? 1 : previous.volume);
    t.paused = e.optBoolean("paused", previous == null ? true : previous.paused);
    t.ended = e.optBoolean("ended", previous == null ? false : previous.ended);
    t.muted = e.optBoolean("muted", previous == null ? false : previous.muted);
    t.userInitiated = e.optBoolean("userInitiated");
    t.width = e.optInt("width", previous == null ? 0 : previous.width);
    t.height = e.optInt("height", previous == null ? 0 : previous.height);
    t.viewportWidth =
        (float) e.optDouble("viewportWidth", previous == null ? 0 : previous.viewportWidth);
    t.viewportHeight =
        (float) e.optDouble("viewportHeight", previous == null ? 0 : previous.viewportHeight);
    t.rect =
        e.has("rect")
            ? rectangle(e.optJSONObject("rect"))
            : previous == null ? new RectF() : new RectF(previous.rect);
    t.clip =
        e.has("clip")
            ? rectangle(e.optJSONObject("clip"))
            : previous == null ? new RectF() : new RectF(previous.clip);
    t.contentClip =
        e.has("contentClip")
            ? rectangle(e.optJSONObject("contentClip"))
            : previous == null ? new RectF(t.rect) : new RectF(previous.contentClip);
    t.topScrollX = (float) e.optDouble("topScrollX", previous == null ? 0 : previous.topScrollX);
    t.topScrollY = (float) e.optDouble("topScrollY", previous == null ? 0 : previous.topScrollY);
    t.visualOffsetX =
        (float) e.optDouble("visualOffsetX", previous == null ? 0 : previous.visualOffsetX);
    t.visualOffsetY =
        (float) e.optDouble("visualOffsetY", previous == null ? 0 : previous.visualOffsetY);
    t.nativeScrollFollow =
        e.has("scrollPolicy")
            ? "document".equals(e.optString("scrollPolicy"))
            : previous == null || previous.nativeScrollFollow;
    t.layoutRevision = e.optLong("layoutRevision", previous == null ? 0 : previous.layoutRevision);
    t.poster = e.optString("poster", previous == null ? "" : previous.poster);
    t.nativeFailed =
        e.optBoolean("nativeFailed")
            || (previous != null
                && previous.sourceVersion == t.sourceVersion
                && previous.nativeFailed);
    if (e.has("qualitySources")) {
      JSONArray values = e.optJSONArray("qualitySources");
      ArrayList<QualitySource> choices = new ArrayList<>();
      if (values != null)
        for (int i = 0; i < Math.min(16, values.length()); i++) {
          JSONObject choice = values.optJSONObject(i);
          if (choice == null
              || !WebResource.isHttp(choice.optString("url"))
              || choice.optString("label").isBlank()) continue;
          String label = choice.optString("label", choice.optInt("height") + "p");
          choices.add(
              new QualitySource(
                  choice.optString("url"),
                  choice.optString("mime"),
                  label.substring(0, Math.min(label.length(), 48)),
                  choice.optInt("width"),
                  choice.optInt("height")));
        }
      t.qualitySources = List.copyOf(choices);
    } else if (previous != null && previous.sourceVersion == t.sourceVersion)
      t.qualitySources = previous.qualitySources;
    t.suppressed = previous != null && previous.suppressed;
    return t;
  }

  private static RectF rectangle(JSONObject o) {
    if (o == null) return new RectF();
    float x = (float) o.optDouble("left", o.optDouble("x", 0));
    float y = (float) o.optDouble("top", o.optDouble("y", 0));
    return new RectF(
        x,
        y,
        (float) o.optDouble("right", x + o.optDouble("width", 0)),
        (float) o.optDouble("bottom", y + o.optDouble("height", 0)));
  }
}
