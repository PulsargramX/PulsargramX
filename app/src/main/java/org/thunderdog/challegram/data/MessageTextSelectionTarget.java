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

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.util.text.TextWrapper;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public final class MessageTextSelectionTarget {
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({Kind.BODY, Kind.CAPTION})
  public @interface Kind {
    int BODY = 0;
    int CAPTION = 1;
  }

  public final long chatId;
  public final long selectionMessageId;
  public final long replyMessageId;
  public final @Kind int kind;
  public final @NonNull TGMessage container;
  public final @Nullable TextWrapper wrapper;
  public final @NonNull MessageTextSelectionSurface surface;
  public final @NonNull TdApi.FormattedText displayedText;
  public final @Nullable TdApi.FormattedText originalText;
  public final boolean canCopy;
  public final boolean canQuote;
  public final boolean canSelectAll;
  public final int layoutRevision;

  public MessageTextSelectionTarget (
    long chatId,
    long selectionMessageId,
    long replyMessageId,
    @Kind int kind,
    @NonNull TGMessage container,
    @NonNull TextWrapper wrapper,
    @NonNull TdApi.FormattedText displayedText,
    @Nullable TdApi.FormattedText originalText,
    boolean canCopy,
    boolean canQuote,
    boolean canSelectAll
  ) {
    this.chatId = chatId;
    this.selectionMessageId = selectionMessageId;
    this.replyMessageId = replyMessageId;
    this.kind = kind;
    this.container = container;
    this.wrapper = wrapper;
    this.surface = new MessageTextSelectionSurface.SingleWrapper(wrapper, displayedText);
    this.displayedText = displayedText;
    this.originalText = originalText;
    this.canCopy = canCopy;
    this.canQuote = canQuote && originalText != null;
    this.canSelectAll = canSelectAll;
    this.layoutRevision = surface.getLayoutRevision();
  }

  public MessageTextSelectionTarget (
    long chatId,
    long selectionMessageId,
    long replyMessageId,
    @Kind int kind,
    @NonNull TGMessage container,
    @NonNull MessageTextSelectionSurface surface,
    @NonNull TdApi.FormattedText displayedText,
    @Nullable TdApi.FormattedText originalText,
    boolean canCopy,
    boolean canQuote,
    boolean canSelectAll
  ) {
    this.chatId = chatId;
    this.selectionMessageId = selectionMessageId;
    this.replyMessageId = replyMessageId;
    this.kind = kind;
    this.container = container;
    this.wrapper = null;
    this.surface = surface;
    this.displayedText = displayedText;
    this.originalText = originalText;
    this.canCopy = canCopy;
    this.canQuote = canQuote && originalText != null;
    this.canSelectAll = canSelectAll;
    this.layoutRevision = surface.getLayoutRevision();
  }

  public boolean hasIdentitySourceMapping () {
    return originalText != null && originalText.text.equals(displayedText.text);
  }
}
