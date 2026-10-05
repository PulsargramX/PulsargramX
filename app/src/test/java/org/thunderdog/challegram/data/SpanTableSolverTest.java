package org.thunderdog.challegram.data;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SpanTableSolverTest {
  @Test
  public void placesRowAndColumnSpansWithoutOverlap () {
    List<SpanTableSolver.InputCell> cells = Arrays.asList(
      cell(0, 0, 2, 2, 100, 160),
      cell(0, 1, 1, 1, 30, 70),
      cell(1, 0, 1, 1, 40, 80),
      cell(2, 0, 3, 1, 150, 240)
    );
    SpanTableSolver.Layout layout = SpanTableSolver.solve(cells, 240, false);
    assertEquals(3, layout.columnCount);
    assertEquals(3, layout.rowCount);
    assertEquals(0, layout.cells.get(0).column);
    assertEquals(2, layout.cells.get(1).column);
    assertEquals(2, layout.cells.get(2).column);
    assertEquals(0, layout.cells.get(3).column);
    assertEquals(layout.width, layout.cellRight(layout.cells.get(3)), .001f);
  }

  @Test
  public void reportsOverflowAndMirrorsCoordinatesForRtl () {
    List<SpanTableSolver.InputCell> cells = Arrays.asList(
      cell(0, 0, 1, 1, 90, 120),
      cell(0, 1, 1, 1, 90, 120)
    );
    SpanTableSolver.Layout ltr = SpanTableSolver.solve(cells, 120, false);
    SpanTableSolver.Layout rtl = SpanTableSolver.solve(cells, 120, true);
    assertTrue(ltr.overflows);
    assertEquals(ltr.width, rtl.width, 0f);
    assertEquals(ltr.cellLeft(ltr.cells.get(0)),
      rtl.width - rtl.cellRight(rtl.cells.get(0)), .001f);
    assertEquals(ltr.cellRight(ltr.cells.get(0)),
      rtl.width - rtl.cellLeft(rtl.cells.get(0)), .001f);
  }

  @Test
  public void limitsMalformedInputToTwentyColumns () {
    SpanTableSolver.Layout layout = SpanTableSolver.solve(Arrays.asList(
      cell(0, 0, 100, 1, 200, 400),
      cell(0, 1, 1, 1, 10, 20)
    ), 500, false);
    assertEquals(SpanTableSolver.MAX_COLUMNS, layout.columnCount);
    assertEquals(1, layout.cells.size());
  }

  private static SpanTableSolver.InputCell cell (
    int row, int sourceColumn, int colspan, int rowspan,
    float minimumWidth, float maximumWidth) {
    return new SpanTableSolver.InputCell(row, sourceColumn, colspan, rowspan,
      minimumWidth, maximumWidth, 20, 40);
  }
}
