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

/** Pure state machine which rejects stale getFullRichMessage callbacks. */
public final class RichMessageRequestGuard {
  public static final class Token {
    final int generation;
    final long chatId;
    final long messageId;
    final String contentIdentity;

    private Token (int generation, long chatId, long messageId,
                   String contentIdentity) {
      this.generation = generation;
      this.chatId = chatId;
      this.messageId = messageId;
      this.contentIdentity = contentIdentity;
    }
  }

  private int generation;
  private boolean inFlight;

  public boolean isInFlight () {
    return inFlight;
  }

  public @Nullable Token begin (long chatId, long messageId,
                                @NonNull String contentIdentity) {
    if (inFlight) {
      return null;
    }
    inFlight = true;
    return new Token(++generation, chatId, messageId, contentIdentity);
  }

  public boolean complete (@NonNull Token token, long chatId, long messageId,
                           @NonNull String contentIdentity) {
    if (token.generation != generation || token.chatId != chatId ||
        token.messageId != messageId ||
        !token.contentIdentity.equals(contentIdentity)) {
      return false;
    }
    inFlight = false;
    return true;
  }

  public void invalidate () {
    generation++;
    inFlight = false;
  }
}
