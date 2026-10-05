/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.data;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure span-aware table placement and width solver shared by Instant View and
 * rich-message bubbles.
 */
public final class SpanTableSolver {
  private SpanTableSolver () { }

  public static final int MAX_COLUMNS = 20;

  public static final class Cell {
    public final int sourceRow;
    public final int sourceColumn;
    public final int column;
    public final int row;
    public final int columnSpan;
    public final int rowSpan;
    public final float minimumWidth;
    public final float maximumWidth;
    public final float minimumHeight;
    public final float maximumHeight;

    private Cell (InputCell input, int column, int row) {
      this.sourceRow = input.sourceRow;
      this.sourceColumn = input.sourceColumn;
      this.column = column;
      this.row = row;
      this.columnSpan = input.columnSpan;
      this.rowSpan = input.rowSpan;
      this.minimumWidth = input.minimumWidth;
      this.maximumWidth = input.maximumWidth;
      this.minimumHeight = input.minimumHeight;
      this.maximumHeight = input.maximumHeight;
    }
  }

  public static final class InputCell {
    public final int sourceRow;
    public final int sourceColumn;
    public final int columnSpan;
    public final int rowSpan;
    public final float minimumWidth;
    public final float maximumWidth;
    public final float minimumHeight;
    public final float maximumHeight;

    public InputCell (int sourceRow, int sourceColumn, int columnSpan, int rowSpan,
                      float minimumWidth, float maximumWidth,
                      float minimumHeight, float maximumHeight) {
      this.sourceRow = sourceRow;
      this.sourceColumn = sourceColumn;
      this.columnSpan = Math.max(1, Math.min(MAX_COLUMNS, columnSpan));
      this.rowSpan = Math.max(1, rowSpan);
      this.minimumWidth = Math.max(0f, minimumWidth);
      this.maximumWidth = Math.max(this.minimumWidth, maximumWidth);
      this.minimumHeight = Math.max(0f, minimumHeight);
      this.maximumHeight = Math.max(this.minimumHeight, maximumHeight);
    }
  }

  public static final class Layout {
    public final @NonNull List<Cell> cells;
    public final int columnCount;
    public final int rowCount;
    public final float[] columns;
    public final float[] rows;
    public final float width;
    public final float height;
    public final boolean overflows;
    public final boolean rtl;

    private Layout (List<Cell> cells, int columnCount, int rowCount, float[] columns,
                    float[] rows, float width, float height, boolean overflows,
                    boolean rtl) {
      this.cells = Collections.unmodifiableList(cells);
      this.columnCount = columnCount;
      this.rowCount = rowCount;
      this.columns = columns;
      this.rows = rows;
      this.width = width;
      this.height = height;
      this.overflows = overflows;
      this.rtl = rtl;
    }

    public float cellLeft (Cell cell) {
      if (!rtl) {
        return columns[cell.column];
      }
      return width - columns[cell.column + cell.columnSpan];
    }

    public float cellRight (Cell cell) {
      if (!rtl) {
        return columns[cell.column + cell.columnSpan];
      }
      return width - columns[cell.column];
    }

    public float cellTop (Cell cell) {
      return rows[cell.row];
    }

    public float cellBottom (Cell cell) {
      return rows[cell.row + cell.rowSpan];
    }
  }

  public static Layout solve (@NonNull List<InputCell> input, float availableWidth, boolean rtl) {
    ArrayList<Cell> placed = place(input);
    int columnCount = 0;
    int rowCount = 0;
    for (Cell cell : placed) {
      columnCount = Math.max(columnCount, cell.column + cell.columnSpan);
      rowCount = Math.max(rowCount, cell.row + cell.rowSpan);
    }
    columnCount = Math.min(MAX_COLUMNS, columnCount);

    float[] minimumColumns = columnMetrics(placed, columnCount, false);
    float[] maximumColumns = columnMetrics(placed, columnCount, true);
    float minimumWidth = sum(minimumColumns);
    float maximumWidth = sum(maximumColumns);
    float targetWidth = Math.max(0f, availableWidth);
    float[] widths = new float[columnCount];
    boolean overflows;
    if (maximumWidth <= targetWidth && columnCount > 0) {
      float extra = (targetWidth - maximumWidth) / columnCount;
      for (int index = 0; index < columnCount; index++) {
        widths[index] = maximumColumns[index] + extra;
      }
      overflows = false;
    } else if (minimumWidth <= targetWidth && maximumWidth > minimumWidth) {
      float factor = (targetWidth - minimumWidth) / (maximumWidth - minimumWidth);
      for (int index = 0; index < columnCount; index++) {
        widths[index] = minimumColumns[index] +
          (maximumColumns[index] - minimumColumns[index]) * factor;
      }
      overflows = false;
    } else {
      System.arraycopy(minimumColumns, 0, widths, 0, columnCount);
      overflows = minimumWidth > targetWidth;
    }

    float interpolation = maximumWidth > minimumWidth ?
      Math.max(0f, Math.min(1f, (sum(widths) - minimumWidth) / (maximumWidth - minimumWidth))) : 0f;
    float[] heights = rowMetrics(placed, rowCount, interpolation);
    float[] columns = coordinates(widths);
    float[] rows = coordinates(heights);
    return new Layout(placed, columnCount, rowCount, columns, rows,
      columns.length > 0 ? columns[columns.length - 1] : 0f,
      rows.length > 0 ? rows[rows.length - 1] : 0f, overflows, rtl);
  }

  private static ArrayList<Cell> place (List<InputCell> input) {
    ArrayList<Cell> result = new ArrayList<>(input.size());
    int[] occupiedUntilRow = new int[MAX_COLUMNS];
    int currentSourceRow = -1;
    int cursor = 0;
    for (InputCell cell : input) {
      if (cell.sourceRow != currentSourceRow) {
        currentSourceRow = cell.sourceRow;
        cursor = 0;
      }
      int span = Math.min(cell.columnSpan, MAX_COLUMNS);
      while (cursor < MAX_COLUMNS && !fits(occupiedUntilRow, cursor, span, cell.sourceRow)) {
        cursor++;
      }
      if (cursor >= MAX_COLUMNS) {
        continue;
      }
      span = Math.min(span, MAX_COLUMNS - cursor);
      InputCell normalized = span == cell.columnSpan ? cell :
        new InputCell(cell.sourceRow, cell.sourceColumn, span, cell.rowSpan,
          cell.minimumWidth, cell.maximumWidth, cell.minimumHeight, cell.maximumHeight);
      result.add(new Cell(normalized, cursor, cell.sourceRow));
      for (int column = cursor; column < cursor + span; column++) {
        occupiedUntilRow[column] = Math.max(occupiedUntilRow[column],
          cell.sourceRow + cell.rowSpan);
      }
      cursor += span;
    }
    return result;
  }

  private static boolean fits (int[] occupiedUntilRow, int column, int span, int row) {
    if (column + span > occupiedUntilRow.length) {
      return false;
    }
    for (int index = column; index < column + span; index++) {
      if (occupiedUntilRow[index] > row) {
        return false;
      }
    }
    return true;
  }

  private static float[] columnMetrics (List<Cell> cells, int count, boolean maximum) {
    float[] result = new float[count];
    for (Cell cell : cells) {
      float width = maximum ? cell.maximumWidth : cell.minimumWidth;
      float part = width / cell.columnSpan;
      int end = Math.min(count, cell.column + cell.columnSpan);
      for (int column = cell.column; column < end; column++) {
        result[column] = Math.max(result[column], part);
      }
    }
    return result;
  }

  private static float[] rowMetrics (List<Cell> cells, int count, float interpolation) {
    float[] result = new float[count];
    for (Cell cell : cells) {
      float height = cell.minimumHeight +
        (cell.maximumHeight - cell.minimumHeight) * interpolation;
      float part = height / cell.rowSpan;
      int end = Math.min(count, cell.row + cell.rowSpan);
      for (int row = cell.row; row < end; row++) {
        result[row] = Math.max(result[row], part);
      }
    }
    return result;
  }

  private static float[] coordinates (float[] sizes) {
    float[] result = new float[sizes.length + 1];
    for (int index = 0; index < sizes.length; index++) {
      result[index + 1] = result[index] + sizes[index];
    }
    return result;
  }

  private static float sum (float[] values) {
    float result = 0f;
    for (float value : values) {
      result += value;
    }
    return result;
  }
}
