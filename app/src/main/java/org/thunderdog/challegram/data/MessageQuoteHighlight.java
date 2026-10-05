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
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.Text;

import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

/** A temporary reply highlight, independent of text selection and search highlights. */
final class MessageQuoteHighlight {
  private static final long MORPH_DELAY = 350L;
  private static final long MORPH_DURATION = 420L;
  private static final long HOLD_DURATION = 2500L;
  private static final long FADE_DURATION = 400L;

  final long messageId;
  private final TdApi.InputTextQuote quote;
  private final Text.SelectionGeometry geometry = new Text.SelectionGeometry();
  private final Path path = new Path();
  private final Path rectanglePath = new Path();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final boolean reduceMotion = Settings.instance().needReduceMotion();
  private @Nullable MessageTextSelectionTarget target;
  private int start = -1;
  private long startTime = -1;
  private boolean positioned;
  private int cornerRadius = -1;
  private int geometryHash;
  private int drawnGeometryHash;
  private boolean finalPath;

  MessageQuoteHighlight (long messageId, @NonNull TdApi.InputTextQuote quote) {
    this.messageId = messageId;
    this.quote = quote;
  }

  private static int findQuoteStart (String text, String quote, int position) {
    if (quote.isEmpty()) {
      return -1;
    }
    if (position < 0) {
      return text.indexOf(quote);
    }
    if (text.startsWith(quote, position)) {
      return position;
    }
    int next = text.indexOf(quote, position);
    int previous = text.lastIndexOf(quote, position);
    if (next == -1) return previous;
    if (previous == -1) return next;
    return next - position < position - previous ? next : previous;
  }

  boolean prepare (@NonNull TGMessage message) {
    if (target == null || !message.isSelectableTextTargetValid(target)) {
      target = message.findQuoteTextTarget(messageId);
      // A translation or pending edit has no reliable mapping to the original quote.
      if (target == null || !target.hasIdentitySourceMapping()) {
        return false;
      }
      start = findQuoteStart(target.displayedText.text, quote.text.text, quote.position);
    }
    boolean ready = start >= 0 && target.surface.buildSelectionGeometry(
      start, start + quote.text.text.length(), geometry
    );
    geometryHash = 1;
    for (int index = 0; index < geometry.getRectangleCount(); index++) {
      geometryHash = 31 * geometryHash + geometry.rectangles.get(index).hashCode();
    }
    return ready;
  }

  @Nullable RectF takeScrollBounds () {
    if (positioned) return null;
    positioned = true;
    return new RectF(geometry.visibleBounds);
  }

  float getFactor () {
    if (startTime == -1) {
      startTime = SystemClock.uptimeMillis();
    }
    if (reduceMotion) return 1f;
    float progress = MathUtils.clamp(
      (SystemClock.uptimeMillis() - startTime - MORPH_DELAY) / (float) MORPH_DURATION
    );
    // Same delay and ease-out quintic curve as mainline Telegram's QuoteHighlight.
    float remaining = 1f - progress;
    return 1f - remaining * remaining * remaining * remaining * remaining;
  }

  float getAlpha () {
    return 1f - MathUtils.clamp(
      (SystemClock.uptimeMillis() - startTime - HOLD_DURATION) / (float) FADE_DURATION
    );
  }

  long getFrameDelay () {
    long elapsed = SystemClock.uptimeMillis() - startTime;
    if (!reduceMotion && elapsed < MORPH_DELAY) return MORPH_DELAY - elapsed;
    if (!reduceMotion && elapsed < MORPH_DELAY + MORPH_DURATION) return 16L;
    return elapsed < HOLD_DURATION ? HOLD_DURATION - elapsed : 16L;
  }

  void draw (Canvas canvas, RectF bounds, float factor, int color) {
    int radius = Math.round(Screen.dp(4f) * factor);
    if (cornerRadius != radius) {
      cornerRadius = radius;
      paint.setPathEffect(radius == 0 ? null : new CornerPathEffect(radius));
    }
    if (factor != 1f || !finalPath || drawnGeometryHash != geometryHash) {
      buildPath(bounds, factor);
      finalPath = factor == 1f;
      drawnGeometryHash = geometryHash;
    }
    paint.setColor(ColorUtils.alphaColor(getAlpha(), color));
    canvas.drawPath(path, paint);
  }

  private void buildPath (RectF bounds, float factor) {
    path.rewind();
    final int count = geometry.getRectangleCount();
    final float padding = Screen.dp(3f);
    for (int index = 0; index < count; index++) {
      RectF rectangle = geometry.rectangles.get(index);
      float sourceTop = bounds.top;
      float sourceBottom = bounds.bottom;
      // All runs on the same visual line start in the same band. This also
      // preserves disconnected runs in mixed RTL/LTR text at the end of the morph.
      for (int otherIndex = 0; factor != 1f && otherIndex < count; otherIndex++) {
        RectF other = geometry.rectangles.get(otherIndex);
        if (other.bottom <= rectangle.top) {
          sourceTop = Math.max(sourceTop, (other.bottom + rectangle.top) / 2f);
        } else if (other.top >= rectangle.bottom) {
          sourceBottom = Math.min(sourceBottom, (rectangle.bottom + other.top) / 2f);
        }
      }
      rectanglePath.rewind();
      rectanglePath.addRect(
        MathUtils.fromTo(bounds.left, rectangle.left - padding, factor),
        MathUtils.fromTo(sourceTop, rectangle.top, factor),
        MathUtils.fromTo(bounds.right, rectangle.right + padding, factor),
        MathUtils.fromTo(sourceBottom, rectangle.bottom, factor),
        Path.Direction.CW
      );
      // Union before rounding avoids seams between lines. Older Android versions
      // retain clockwise contours in one winding path, so overlaps still draw once.
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT ||
          !Api19.union(path, rectanglePath)) {
        path.addPath(rectanglePath);
      }
    }
  }

  private static final class Api19 {
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    static boolean union (Path path, Path rectangle) {
      return path.op(rectangle, Path.Op.UNION);
    }
  }
}
