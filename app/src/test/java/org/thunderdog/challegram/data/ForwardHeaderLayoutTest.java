package org.thunderdog.challegram.data;

import org.junit.Test;

import static org.junit.Assert.*;

public class ForwardHeaderLayoutTest {
  private static ForwardHeaderLayout layout (int width, float name, float time, float counters) {
    return new ForwardHeaderLayout(width, name, time, counters, 6, 80);
  }

  @Test
  public void keepsReadableHeadersOnOneLine () {
    ForwardHeaderLayout result = layout(300, 140, 60, 70);
    assertFalse(result.metadataOnNewLine);
    assertEquals(164, result.nameMaxWidth);
    assertTrue(result.timeMaxWidth >= 60);
  }

  @Test
  public void preservesShortNamesWithoutAddingARow () {
    ForwardHeaderLayout result = layout(200, 24, 90, 70);
    assertFalse(result.metadataOnNewLine);
    assertEquals(34, result.nameMaxWidth);
  }

  @Test
  public void givesSourceItsOwnRowWhenMetadataWouldHideIt () {
    ForwardHeaderLayout result = layout(180, 120, 110, 70);
    assertTrue(result.metadataOnNewLine);
    assertEquals(180, result.nameMaxWidth);
    assertEquals(110, result.timeMaxWidth);
  }

  @Test
  public void wrapsBeforeOnlyAnEllipsisWouldRemain () {
    ForwardHeaderLayout result = layout(200, 120, 90, 70);
    assertTrue(result.metadataOnNewLine);
    assertEquals(200, result.nameMaxWidth);
  }

  @Test
  public void alsoProtectsUserForwardsWithoutCounters () {
    ForwardHeaderLayout result = layout(160, 120, 110, 0);
    assertTrue(result.metadataOnNewLine);
    assertEquals(160, result.nameMaxWidth);
    assertEquals(160, result.timeMaxWidth);
  }

  @Test
  public void changesRowsAtTheReadableNameBoundary () {
    assertFalse(layout(256, 120, 100, 70).metadataOnNewLine);
    assertTrue(layout(255, 120, 100, 70).metadataOnNewLine);
  }

  @Test
  public void allowsLongNamesToEllipsizeInlineWhenReadable () {
    ForwardHeaderLayout result = layout(300, 500, 100, 70);
    assertFalse(result.metadataOnNewLine);
    assertEquals(124, result.nameMaxWidth);
  }

  @Test
  public void boundsLongDatesOnTheSecondRow () {
    ForwardHeaderLayout result = layout(160, 120, 200, 70);
    assertTrue(result.metadataOnNewLine);
    assertEquals(160, result.nameMaxWidth);
    assertEquals(90, result.timeMaxWidth);
  }

  @Test
  public void handlesCountersWiderThanTheBubble () {
    ForwardHeaderLayout result = layout(60, 100, 100, 80);
    assertTrue(result.metadataOnNewLine);
    assertEquals(60, result.nameMaxWidth);
    assertEquals(0, result.timeMaxWidth);
  }

  @Test
  public void reflowsInBothDirectionsAsCountersAndDatesChange () {
    assertFalse(layout(240, 100, 80, 60).metadataOnNewLine);
    assertTrue(layout(240, 100, 110, 60).metadataOnNewLine);
    assertTrue(layout(240, 100, 80, 90).metadataOnNewLine);
    assertFalse(layout(240, 100, 80, 60).metadataOnNewLine);
  }

  @Test
  public void roundsFractionalMeasurementsWithoutOverlapping () {
    ForwardHeaderLayout result = layout(240, 100, 80.5f, 60.5f);
    assertFalse(result.metadataOnNewLine);
    assertEquals(93, result.nameMaxWidth);
    assertTrue(result.nameMaxWidth + 6 + 80.5f + 60.5f <= 240);
  }

  @Test
  public void neverAllocatesNegativeSpace () {
    for (int width = -10; width <= 400; width++) {
      ForwardHeaderLayout result = layout(width, 140, 110, 70);
      assertTrue(result.nameMaxWidth >= 0);
      assertTrue(result.timeMaxWidth >= 0);
      assertTrue(result.nameMaxWidth <= Math.max(0, width));
      if (width > 0) {
        assertTrue(result.nameMaxWidth > 0);
      }
    }
  }
}
