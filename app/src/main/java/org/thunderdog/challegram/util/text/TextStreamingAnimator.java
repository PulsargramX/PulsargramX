/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.util.text;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.tool.Screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Keeps a visual caret moving between incoming bot draft updates. */
public final class TextStreamingAnimator implements Choreographer.FrameCallback {
  private static final float TARGET_DURATION_SEC = 1.05f;
  private static final float MIN_SPEED_DP_PER_SEC = 40f;
  private static final float FADE_WIDTH_DP = 50f;

  public static final class Block {
    final String key;
    final String source;
    final int[] widths;
    final @Nullable Text layout;

    public Block (String key, @Nullable TextWrapper wrapper) {
      this.key = key;
      source = wrapper != null ? wrapper.getText() : "";
      Text text = wrapper != null ? wrapper.getCurrent() : null;
      layout = text;
      widths = new int[text != null ? text.getLineCount() : 1];
      for (int line = 0; line < widths.length; line++) {
        widths[line] = text != null ? text.getLineWidth(line) : Screen.dp(8f);
      }
    }
  }

  private final Runnable invalidate;
  private final ArrayList<Block> blocks = new ArrayList<>();
  private final HashMap<String, Integer> indices = new HashMap<>();
  private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final LinearGradient gradient = new LinearGradient(0, 0, 1, 0,
    0xffffffff, 0x00ffffff, Shader.TileMode.CLAMP);
  private final Matrix gradientMatrix = new Matrix();
  private final RectF lineBounds = new RectF();
  private final Text.StreamingPosition cursor = new Text.StreamingPosition();
  private boolean cursorSaved;
  private int blockIndex;
  private int lineIndex;
  private float position;
  private float speed;
  private long lastFrame;
  private boolean attached;
  private boolean wasAttached;
  private boolean finished = true;
  private boolean scheduled;

  public TextStreamingAnimator (Runnable invalidate) {
    this.invalidate = invalidate;
    mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    mask.setShader(gradient);
  }

  public void setBlocks (List<Block> next) {
    String activeKey = blocks.isEmpty() ? null : blocks.get(blockIndex).key;
    boolean append = next.size() >= blocks.size();
    for (int index = 0; append && index < blocks.size(); index++) {
      Block previous = blocks.get(index);
      Block current = next.get(index);
      append = previous.key.equals(current.key) &&
        (index < blockIndex ? previous.source.equals(current.source) :
          current.source.startsWith(previous.source));
    }
    blocks.clear();
    blocks.addAll(next);
    indices.clear();
    for (int index = 0; index < blocks.size(); index++) {
      indices.put(blocks.get(index).key, index);
    }
    if (!append || activeKey != null && !indices.containsKey(activeKey)) {
      finish();
      return;
    }
    blockIndex = activeKey != null ? indices.get(activeKey) : 0;
    if (!blocks.isEmpty()) {
      Block block = blocks.get(blockIndex);
      int[] widths = block.widths;
      if (cursorSaved && block.layout != null && block.layout.restoreStreamingPosition(cursor)) {
        lineIndex = cursor.lineIndex;
        position = cursor.advance;
      }
      lineIndex = Math.min(lineIndex, Math.max(0, widths.length - 1));
      position = widths.length == 0 ? 0f : Math.min(position, widths[lineIndex]);
    }
    cursorSaved = false;
    float remaining = remainingPixels();
    finished = remaining <= 0f;
    speed = Math.max(Screen.dp(MIN_SPEED_DP_PER_SEC), remaining / TARGET_DURATION_SEC);
    if (wasAttached && !attached) {
      finish();
      return;
    }
    schedule();
  }

  /** Capture source position before a wrapper is destroyed or its lines are reflowed. */
  public void saveCursor () {
    if (!cursorSaved && !blocks.isEmpty()) {
      Text layout = blocks.get(blockIndex).layout;
      cursorSaved = layout != null && layout.getStreamingPosition(lineIndex, position, cursor);
    }
  }

  public void attach () {
    attached = true;
    wasAttached = true;
    schedule();
  }

  public void detach () {
    attached = false;
    // Offscreen drafts should show their latest content when the user returns.
    finish();
  }

  public void finish () {
    if (scheduled) {
      Choreographer.getInstance().removeFrameCallback(this);
      scheduled = false;
    }
    lastFrame = 0;
    cursorSaved = false;
    finished = true;
    if (!blocks.isEmpty()) {
      blockIndex = blocks.size() - 1;
      int[] widths = blocks.get(blockIndex).widths;
      lineIndex = Math.max(0, widths.length - 1);
      position = widths.length == 0 ? 0f : widths[lineIndex];
    }
    invalidate.run();
  }

  private void schedule () {
    if (attached && !finished && !scheduled) {
      scheduled = true;
      Choreographer.getInstance().postFrameCallback(this);
    }
  }

  @Override
  public void doFrame (long frameTimeNanos) {
    scheduled = false;
    if (!attached || finished) return;
    if (lastFrame != 0) {
      // Avoid jumping through a long answer after a stalled frame or backgrounding.
      float delta = speed * Math.min(.05f, (frameTimeNanos - lastFrame) * 1e-9f);
      while (blockIndex < blocks.size()) {
        int[] widths = blocks.get(blockIndex).widths;
        float remaining = lineIndex < widths.length ? widths[lineIndex] - position : 0f;
        if (remaining > delta) {
          position += delta;
          break;
        }
        delta -= Math.max(0f, remaining);
        if (lineIndex + 1 < widths.length) {
          lineIndex++;
        } else if (blockIndex + 1 < blocks.size()) {
          blockIndex++;
          lineIndex = 0;
        } else {
          position = widths.length == 0 ? 0f : widths[lineIndex];
          finished = true;
          break;
        }
        position = 0f;
      }
    }
    lastFrame = finished ? 0 : frameTimeNanos;
    invalidate.run();
    schedule();
  }

  private float remainingPixels () {
    float remaining = 0f;
    for (int index = blockIndex; index < blocks.size(); index++) {
      int[] widths = blocks.get(index).widths;
      for (int line = index == blockIndex ? lineIndex : 0; line < widths.length; line++) {
        remaining += Math.max(0f,
          widths[line] - (index == blockIndex && line == lineIndex ? position : 0f));
      }
    }
    return remaining;
  }

  public boolean isVisible (String key) {
    Integer index = indices.get(key);
    return finished || index == null || index <= blockIndex;
  }

  public float alpha (String key) {
    Integer index = indices.get(key);
    if (finished || index == null || index < blockIndex) return 1f;
    if (index > blockIndex) return 0f;
    if (lineIndex > 0) return 1f;
    return Math.min(1f, position / Screen.dp(8f));
  }

  public void draw (String key, TextWrapper wrapper, Canvas canvas, int left, int right,
                    int endPadding, int top, float alpha, @Nullable ComplexReceiver receiver) {
    Integer index = indices.get(key);
    if (finished || index == null || index < blockIndex) {
      wrapper.draw(canvas, left, right, endPadding, top, null, alpha, receiver);
      return;
    }
    if (index > blockIndex) return;
    Text text = wrapper.getCurrent();
    if (text == null || lineIndex >= text.getLineCount()) return;
    int lineTop = top + text.getLineTop(lineIndex);
    int lineBottom = lineTop + text.getLineHeight(lineIndex);
    // Some text messages pass the same start/end X for a single LTR line.
    int clipRight = Math.max(right, left + text.getMaxWidth());
    int save = canvas.save();
    canvas.clipRect(left, top, clipRight, lineTop);
    wrapper.draw(canvas, left, right, endPadding, top, null, alpha, receiver);
    canvas.restoreToCount(save);
    // Drawing above also records the real text coordinates, including center/RTL alignment.
    boolean rtl = text.getLineBounds(lineIndex, lineBounds);
    float width = lineBounds.width();
    if (width <= 0f || position <= 0f) return;
    float progress = Math.min(1f, position / Math.max(1, text.getLineWidth(lineIndex)));
    float fadeWidth = Screen.dp(FADE_WIDTH_DP);
    float edge = -fadeWidth + (width + fadeWidth) * progress;
    float start = rtl ? lineBounds.right - edge : lineBounds.left + edge;
    gradientMatrix.setScale(rtl ? -fadeWidth : fadeWidth, 1f);
    gradientMatrix.postTranslate(start, 0);
    gradient.setLocalMatrix(gradientMatrix);
    save = canvas.saveLayer(left, lineTop, clipRight, lineBottom, null);
    canvas.clipRect(left, lineTop, clipRight, lineBottom);
    wrapper.draw(canvas, left, right, endPadding, top, null, alpha, receiver);
    canvas.drawRect(left, lineTop, clipRight, lineBottom, mask);
    canvas.restoreToCount(save);
  }
}
