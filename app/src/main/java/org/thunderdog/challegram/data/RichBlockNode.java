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

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

import org.thunderdog.challegram.loader.ComplexReceiver;

import me.vkryl.core.lambda.Destroyable;

/** Shared lifecycle and interaction contract for one flattened rich block. */
public interface RichBlockNode extends Destroyable {
  @NonNull String path ();
  int constructor ();
  int measure (int width);
  int width ();
  int height ();
  int top ();
  void setTop (int top);
  void draw (@NonNull View view, @NonNull Canvas canvas, int x, int y,
             @NonNull ComplexReceiver receiver);
  boolean onTouchEvent (@NonNull View view, @NonNull MotionEvent event, int x, int y);
  void requestMedia (@NonNull ComplexReceiver receiver);
  void attach (@NonNull View view);
  void detach (@NonNull View view);
  void getBounds (@NonNull Rect out);
}
