/*
 * This file is a part of Pulsargram X, based on Telegram X.
 * Copyright © 2026 Pulsargram X contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See <https://www.gnu.org/licenses/> for the GNU General Public License.
 */
package org.thunderdog.challegram.component.webapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.widget.Button;

/** Telegram's optional button shine, paused automatically with a hidden or disabled button. */
public final class WebAppActionButton extends Button {
  private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path clip = new Path();
  private final RectF bounds = new RectF();
  private boolean shine;
  private long started;

  public WebAppActionButton (Context context) { super(context); }

  public void setShine (boolean shine) {
    if (this.shine == shine) return;
    this.shine = shine;
    started = SystemClock.uptimeMillis();
    invalidate();
  }

  @Override protected void onSizeChanged (int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    float radius = 8 * getResources().getDisplayMetrics().density;
    clip.reset();
    bounds.set(0, 0, width, height);
    clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
    shinePaint.setShader(new LinearGradient(0, 0, Math.max(1, width * .45f), height,
      new int[] {0x00ffffff, 0x44ffffff, 0x00ffffff}, new float[] {0, .5f, 1}, Shader.TileMode.CLAMP));
  }

  @Override protected void onDraw (Canvas canvas) {
    super.onDraw(canvas);
    if (!shine || !isEnabled() || !isShown()) return;
    long phase = (SystemClock.uptimeMillis() - started) % 3000;
    if (phase < 1100) {
      int save = canvas.save();
      canvas.clipPath(clip);
      canvas.translate(getWidth() * (-.5f + 1.6f * phase / 1100f), 0);
      canvas.drawRect(0, 0, getWidth() * .45f, getHeight(), shinePaint);
      canvas.restoreToCount(save);
      postInvalidateOnAnimation();
    } else {
      postInvalidateDelayed(3000 - phase);
    }
  }
}
