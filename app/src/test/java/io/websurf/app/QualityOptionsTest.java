package io.websurf.app;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public final class QualityOptionsTest {
  private static QualityOptions.Choice quality(int width, int height) {
    return new QualityOptions.Choice("test", width, height, false, null, null, 0);
  }

  @Test
  public void preferenceUsesSupportedLowerQualityAndFallsBackToLowest() {
    QualityOptions.Choice low = quality(640, 360), high = quality(1920, 1080);
    List<QualityOptions.Choice> choices = List.of(high, low);
    assertSame(low, QualityOptions.preferred(choices, 720));
    assertSame(high, QualityOptions.preferred(choices, 2160));
    assertSame(low, QualityOptions.preferred(choices, 240));
    assertNull(QualityOptions.preferred(List.of(), 720));
  }

  @Test
  public void portraitPreferenceUsesShortEdgeAndIgnoresAutomaticEntry() {
    QualityOptions.Choice automatic = new QualityOptions.Choice("自动", 0, 0, true, null, null, -1);
    QualityOptions.Choice portrait = quality(720, 1280), larger = quality(1080, 1920);
    assertEquals(720, portrait.resolution());
    assertSame(portrait, QualityOptions.preferred(List.of(automatic, larger, portrait), 720));
  }
}
