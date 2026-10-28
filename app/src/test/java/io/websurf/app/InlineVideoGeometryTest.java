package io.websurf.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class InlineVideoGeometryTest {
  @Test
  public void ordinaryScrollHasNoTimeBasedLag() {
    for (int y = 200; y >= 0; y--) {
      var p = InlineVideoGeometry.place(16, y, 368, 207, 400, true, true, false);
      assertEquals(y, p.top(), 0);
      assertEquals(368, p.width(), 0);
      assertFalse(p.pinned());
    }
  }

  @Test
  public void pinningExpandsContinuouslyWithScrollDistance() {
    var start = InlineVideoGeometry.place(16, -.001f, 368, 207, 400, true, true, false);
    var halfway = InlineVideoGeometry.place(16, -9, 368, 207, 400, true, true, true);
    var end = InlineVideoGeometry.place(16, -18, 368, 207, 400, true, true, true);
    assertEquals(0, start.top(), 0);
    assertEquals(368, start.width(), .01f);
    assertEquals(8, halfway.left(), .001f);
    assertEquals(384, halfway.width(), .001f);
    assertEquals(225, end.height(), .001f);
    assertEquals(400, end.width(), .001f);
  }

  @Test
  public void returningScrollReversesSameGeometryAndUnpins() {
    var forward = InlineVideoGeometry.place(16, -9, 368, 207, 400, true, true, false);
    var reverse = InlineVideoGeometry.place(16, -9, 368, 207, 400, true, true, true);
    assertEquals(forward, reverse);
    assertFalse(InlineVideoGeometry.place(16, 0, 368, 207, 400, true, true, true).pinned());
  }

  @Test
  public void pauseRetainsExistingPinButCannotStartOne() {
    assertFalse(InlineVideoGeometry.place(16, -12, 368, 207, 400, true, false, false).pinned());
    assertTrue(InlineVideoGeometry.place(16, -12, 368, 207, 400, true, false, true).pinned());
  }

  @Test
  public void fixedAndHiddenVideosDoNotAutoPin() {
    assertFalse(InlineVideoGeometry.place(16, -12, 368, 207, 400, false, true, true).pinned());
  }

  @Test
  public void equalHeightAndPortraitCasesStayFinite() {
    for (float h : new float[] {225, 640})
      for (int d = 0; d < 800; d++) {
        var p = InlineVideoGeometry.place(16, -d, 368, h, 400, true, true, true);
        assertTrue(Float.isFinite(p.width()));
        assertTrue(Float.isFinite(p.height()));
        assertTrue(p.height() > 0);
        assertTrue(p.expansion() >= 0 && p.expansion() <= 1);
      }
  }
}
