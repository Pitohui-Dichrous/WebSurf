package io.websurf.app;

/** Scroll-driven page-to-top transition. No clock or animation queue participates in tracking. */
final class InlineVideoGeometry {
  record Placement(
      float left, float top, float width, float height, boolean pinned, float expansion) {}

  static Placement place(
      float left,
      float top,
      float width,
      float height,
      float viewportWidth,
      boolean eligible,
      boolean playing,
      boolean wasPinned) {
    if (!eligible || top >= 0 || (!playing && !wasPinned))
      return new Placement(left, top, width, height, false, 0);
    float targetHeight = viewportWidth * 9f / 16f;
    float distance = -top;
    float heightDelta = targetHeight - height;
    float span = Math.abs(heightDelta);
    // The normal 16:9/full-width case needs no expansion. Avoid QQ's degenerate division.
    float t =
        span > .01f
            ? Math.min(1, distance / span)
            : Math.min(1, distance / Math.max(1, Math.abs(viewportWidth - width) + Math.abs(left)));
    return new Placement(
        left * (1 - t), 0, width + (viewportWidth - width) * t, height + heightDelta * t, true, t);
  }
}
