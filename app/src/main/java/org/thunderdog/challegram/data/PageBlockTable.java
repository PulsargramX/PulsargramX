/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.ListItem;
import org.thunderdog.challegram.util.DrawableProvider;
import org.thunderdog.challegram.util.text.FormattedText;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSets;
import org.thunderdog.challegram.widget.PageBlockView;

import java.util.ArrayList;

import me.vkryl.core.lambda.Destroyable;
import tgx.td.Td;

public class PageBlockTable extends PageBlock implements Destroyable {
  private static final int MARGIN_BOTTOM = 6;
  private static final int MARGIN_HORIZONTAL = 12;
  private static final int PADDING_HORIZONTAL = 8;
  private static final int PADDING_VERTICAL = 8;

  private final Cell[] cellsList;

  public PageBlockTable (ViewController<?> context, TdApi.PageBlockTable block, int quoteLevel, @Nullable TdlibUi.UrlOpenParameters openParameters) {
    super(context, block, quoteLevel);

    final ArrayList<SpanTableSolver.InputCell> input = new ArrayList<>();
    if (block.cells != null) {
      for (int row = 0; row < block.cells.length; row++) {
        TdApi.PageBlockTableCell[] rowCells = block.cells[row];
        if (rowCells == null) {
          continue;
        }
        for (int column = 0; column < rowCells.length; column++) {
          TdApi.PageBlockTableCell cell = rowCells[column];
          if (cell != null) {
            input.add(new SpanTableSolver.InputCell(row, column, cell.colspan, cell.rowspan,
              0, 0, 0, 0));
          }
        }
      }
    }
    SpanTableSolver.Layout placement = SpanTableSolver.solve(input, 0, false);
    final ArrayList<Cell> cellsList = new ArrayList<>(placement.cells.size());
    for (SpanTableSolver.Cell solved : placement.cells) {
      TdApi.PageBlockTableCell cell = block.cells[solved.sourceRow][solved.sourceColumn];
      cellsList.add(new Cell(this, cell, solved.sourceRow, solved.sourceColumn,
        solved.column, solved.row,
        FormattedText.parseRichText(context, cell.text, openParameters)));
    }

    this.cellsList = cellsList.toArray(new Cell[0]);
  }

  @Override
  public int getRelatedViewType () {
    return ListItem.TYPE_PAGE_BLOCK_TABLE;
  }

  @Override
  public void requestIcons (ComplexReceiver receiver) {
    int iconCount = 0;
    for (Cell cell : cellsList) {
      if (cell.text != null) {
        cell.text.requestMedia(receiver, iconCount, cell.iconCount);
      }
      iconCount += cell.iconCount;
    }
    receiver.clearReceiversWithHigherKey(iconCount);
  }

  private int tableHeight;
  private int customTableWidth;

  @Override
  protected int computeHeight (View view, final int maxContentWidth) {
    final int horizontalMargin = Screen.dp(MARGIN_HORIZONTAL);
    final int topMargin = getContentTop();
    final int bottomMargin = Screen.dp(MARGIN_BOTTOM);
    final int defaultWidth = (maxContentWidth - horizontalMargin * 2);

    for (Cell cell : cellsList) {
      cell.prepareToBuild(defaultWidth);
    }

    ArrayList<SpanTableSolver.InputCell> input = new ArrayList<>(cellsList.length);
    for (Cell cell : cellsList) {
      input.add(new SpanTableSolver.InputCell(cell.sourceRow, cell.sourceColumn,
        cell.cell.colspan, cell.cell.rowspan,
        cell.width(Cell.METRIC_TYPE_MIN), cell.width(Cell.METRIC_TYPE_MAX),
        cell.height(Cell.METRIC_TYPE_MIN), cell.height(Cell.METRIC_TYPE_MAX)));
    }
    SpanTableSolver.Layout layout = SpanTableSolver.solve(input, defaultWidth, false);
    float[] rowHeights = new float[layout.rowCount];
    for (SpanTableSolver.Cell solved : layout.cells) {
      Cell cell = findCell(solved.sourceRow, solved.sourceColumn);
      if (cell == null) {
        continue;
      }
      int width = (int) Math.ceil(layout.cellRight(solved) - layout.cellLeft(solved) + 2);
      cell.build(width);
      float rowHeight = (float) cell.height() / solved.rowSpan;
      for (int row = solved.row;
           row < solved.row + solved.rowSpan && row < rowHeights.length; row++) {
        rowHeights[row] = Math.max(rowHeights[row], rowHeight);
      }
    }
    float[] tableCordsY = coordinates(rowHeights);

    customTableWidth = Math.round(layout.width);

    for (SpanTableSolver.Cell solved : layout.cells) {
      Cell cell = findCell(solved.sourceRow, solved.sourceColumn);
      if (cell == null) {
        continue;
      }
      cell.bounds.set(
        Math.round(layout.cellLeft(solved)),
        Math.round(tableCordsY[solved.row]),
        Math.round(layout.cellRight(solved)),
        Math.round(tableCordsY[solved.row + solved.rowSpan]));
      cell.bounds.offset(horizontalMargin, topMargin);
      if (cell.text != null && cell.text.hasMedia()) {
        cell.text.notifyMediaChanged(null);
      }
    }

    this.tableHeight = Math.round(tableCordsY[tableCordsY.length - 1]);

    return topMargin + tableHeight + bottomMargin;
  }

  private @Nullable Cell findCell (int sourceRow, int sourceColumn) {
    for (Cell cell : cellsList) {
      if (cell.sourceRow == sourceRow && cell.sourceColumn == sourceColumn) {
        return cell;
      }
    }
    return null;
  }

  private static float[] coordinates (float[] sizes) {
    float[] result = new float[sizes.length + 1];
    for (int index = 0; index < sizes.length; index++) {
      result[index + 1] = result[index] + sizes[index];
    }
    return result;
  }

  @Override
  public int getCustomWidth () {
    return customTableWidth + Screen.dp(MARGIN_HORIZONTAL) * 2;
  }

  @Override
  public boolean handleTouchEvent (View view, MotionEvent e) {
    for (Cell cell : cellsList) {
      if (cell.text != null && cell.text.onTouchEvent(view, e, context instanceof Text.ClickCallback ? (Text.ClickCallback) context : null))
        return true;
    }
    return false;
  }

  @Override
  protected int getContentTop () {
    return Screen.dp(Td.isEmpty(((TdApi.PageBlockTable) block).caption) ? 6f : 2f);
  }

  @Override
  protected int getContentHeight () {
    return this.tableHeight;
  }

  @Override
  protected <T extends View & DrawableProvider> void drawInternal (T view, Canvas c, Receiver preview, Receiver receiver, @Nullable ComplexReceiver iconReceiver) {
    final TdApi.PageBlockTable table = (TdApi.PageBlockTable) this.block;
    for (Cell cell : cellsList) {
      if (cell.cell.isHeader || (table.isStriped && cell.cellPositionStartY() % 2 == 0)) {
        c.drawRect(cell.bounds, Paints.fillingPaint(Theme.backgroundColor()));
      }
      if (table.isBordered) {
        c.drawRect(cell.bounds, Paints.strokeSeparatorPaint(Theme.separatorColor()));
      }
      if (cell.text != null) {
        int restoreCount = Views.save(c);
        c.clipRect(cell.bounds);
        int y;
        switch (cell.cell.valign.getConstructor()) {
          case TdApi.PageBlockVerticalAlignmentTop.CONSTRUCTOR:
            y = cell.bounds.top + Screen.dp(PADDING_VERTICAL);
            break;
          case TdApi.PageBlockVerticalAlignmentMiddle.CONSTRUCTOR:
            y = cell.bounds.centerY() - (cell.text.getHeight() - cell.text.getLineSpacing()) / 2;
            break;
          case TdApi.PageBlockVerticalAlignmentBottom.CONSTRUCTOR:
            y = cell.bounds.bottom - Screen.dp(PADDING_VERTICAL) - cell.text.getHeight();
            break;
          default:
            throw new UnsupportedOperationException(cell.toString());
        }
        cell.text.draw(c, cell.bounds.left + Screen.dp(PADDING_HORIZONTAL), cell.bounds.right - Screen.dp(PADDING_VERTICAL), 0, y, null, 1f, iconReceiver);
        Views.restore(c, restoreCount);
      }
    }
  }

  @Override
  public void performDestroy () {
    for (Cell cell : cellsList) {
      cell.performDestroy();
    }
  }

  private static class Cell implements Destroyable {
    private static final int METRIC_TYPE_MAX = 0;
    private static final int METRIC_TYPE_MIN = 1;
    private static final int METRIC_TYPE_CURRENT = 2;

    private final PageBlockTable parent;
    private final @NonNull TdApi.PageBlockTableCell cell;
    private final int sourceRow, sourceColumn;
    private final int cellPositionX, cellPositionY;
    private final int iconCount;

    private int heightForMinimalPossibleWidth;
    private int heightForMaximalPossibleWidth;
    private int minimalPossibleWidth;
    private int maximalPossibleWidth;

    private final @Nullable FormattedText formattedText;
    private @Nullable Text text;

    private final Rect bounds = new Rect();

    public Cell (PageBlockTable parent, @NonNull TdApi.PageBlockTableCell cell,
                 int sourceRow, int sourceColumn, int cellPositionX, int cellPositionY,
                 @Nullable FormattedText formattedText) {
      this.parent = parent;
      this.cell = cell;
      this.sourceRow = sourceRow;
      this.sourceColumn = sourceColumn;
      this.cellPositionX = cellPositionX;
      this.cellPositionY = cellPositionY;
      this.formattedText = formattedText;
      this.iconCount = formattedText != null ? formattedText.getIconCount() : 0;
    }

    public int width (int metricType) {
      if (metricType == METRIC_TYPE_CURRENT) {
        return width();
      }
      return ((metricType == METRIC_TYPE_MIN ? minimalPossibleWidth : maximalPossibleWidth) + Screen.dp(PADDING_HORIZONTAL) * 2);
    }

    public int height (int metricType) {
      if (metricType == METRIC_TYPE_CURRENT) {
        return height();
      }
      return (metricType == METRIC_TYPE_MIN ? heightForMinimalPossibleWidth : heightForMaximalPossibleWidth) + Screen.dp(PADDING_VERTICAL) * 2;
    }

    public int cellPositionStartX () {
      return cellPositionX;
    }

    public int cellPositionEndX () {
      return cellPositionX + cell.colspan;
    }

    public int cellPositionStartY () {
      return cellPositionY;
    }

    public int cellPositionEndY () {
      return cellPositionY + cell.rowspan;
    }

    public int width () {
      return text != null ? text.getWidth() + Screen.dp(PADDING_HORIZONTAL) * 2 : 0;
    }

    public int height () {
      return text != null ? text.getHeight() + Screen.dp(PADDING_VERTICAL) * 2 : 0;
    }

    public void prepareToBuild (int maxCellWidth) {
      if (formattedText != null) {
        if (text != null) {
          text.performDestroy();
        }

        final int maxTextWidth = maxCellWidth - Screen.dp(PADDING_HORIZONTAL) * 2;

        Text.Builder b = new Text.Builder(formattedText.text, maxTextWidth, PageBlockRichText.getParagraphProvider(), TextColorSets.InstantView.NORMAL)
          .entities(formattedText.entities, (text, specificMedia) -> {
            for (View view : parent.currentViews) {
              if (view instanceof PageBlockView) {
                if (!text.invalidateMediaContent(((PageBlockView) view).getIconReceiver(), specificMedia)) {
                  ((PageBlockView) view).invalidateIconsContent(parent);
                }
              }
            }
          })
          .textFlags(Text.FLAG_ARTICLE | Text.FLAG_CUSTOM_LONG_PRESS | Text.FLAG_ALWAYS_BREAK)
          .viewProvider(parent.currentViews);
        switch (cell.align.getConstructor()) {
          case TdApi.PageBlockHorizontalAlignmentLeft.CONSTRUCTOR:
            break;
          case TdApi.PageBlockHorizontalAlignmentCenter.CONSTRUCTOR:
            b.addFlags(Text.FLAG_ALIGN_CENTER);
            break;
          case TdApi.PageBlockHorizontalAlignmentRight.CONSTRUCTOR:
            b.addFlags(Text.FLAG_ALIGN_RIGHT);
            break;
        }
        text = b.build();

        minimalPossibleWidth = text.getWidth();
        heightForMinimalPossibleWidth = text.getHeight();

        text.setTextFlag(Text.FLAG_ALWAYS_BREAK, false);
        text.changeMaxWidth(maxTextWidth, true);

        maximalPossibleWidth = text.getWidth();
        heightForMaximalPossibleWidth = text.getHeight();
      } else {
        text = null;
        minimalPossibleWidth = 0;
        heightForMinimalPossibleWidth = 0;
        maximalPossibleWidth = 0;
        heightForMaximalPossibleWidth = 0;
      }
    }

    public void build (int maxCellWidth) {
      if (text != null) {
        text.changeMaxWidth(maxCellWidth - Screen.dp(PADDING_HORIZONTAL) * 2);
      }
    }

    @Override
    public void performDestroy () {
      if (text != null) {
        text.performDestroy();
      }
    }
  }
}
