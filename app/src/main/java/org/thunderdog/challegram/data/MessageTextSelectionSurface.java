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
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextWrapper;

/** Geometry and copy boundary used by both single and composite message text. */
public interface MessageTextSelectionSurface {
  boolean hitTestSelection (float x, float y, @NonNull Text.SelectionHit out, boolean clampToText);
  boolean buildSelectionGeometry (int start, int end, @NonNull Text.SelectionGeometry out);
  void setSelection (int start, int end);
  void clearSelection ();
  int getLayoutRevision ();
  @NonNull String copyPlainText (int start, int end);
  @Nullable String copyHtml (int start, int end);

  final class SingleWrapper implements MessageTextSelectionSurface {
    private final @NonNull TextWrapper wrapper;
    private final @NonNull TdApi.FormattedText text;

    public SingleWrapper (@NonNull TextWrapper wrapper, @NonNull TdApi.FormattedText text) {
      this.wrapper = wrapper;
      this.text = text;
    }

    @Override
    public boolean hitTestSelection (float x, float y, @NonNull Text.SelectionHit out,
                                     boolean clampToText) {
      return wrapper.hitTestSelection(x, y, out, clampToText);
    }

    @Override
    public boolean buildSelectionGeometry (int start, int end, @NonNull Text.SelectionGeometry out) {
      return wrapper.buildSelectionGeometry(start, end, out);
    }

    @Override
    public void setSelection (int start, int end) {
      wrapper.setSelection(start, end);
    }

    @Override
    public void clearSelection () {
      wrapper.clearSelection();
    }

    @Override
    public int getLayoutRevision () {
      return wrapper.getLayoutRevision();
    }

    @NonNull
    @Override
    public String copyPlainText (int start, int end) {
      int from = Math.max(0, Math.min(start, text.text.length()));
      int to = Math.max(from, Math.min(end, text.text.length()));
      return text.text.substring(from, to);
    }

    @Nullable
    @Override
    public String copyHtml (int start, int end) {
      return null;
    }
  }
}
