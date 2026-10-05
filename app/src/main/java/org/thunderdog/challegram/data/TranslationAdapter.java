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

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;

/**
 * Typed boundary between the shared translation UI/state machine and
 * protocol-specific translation functions.
 */
public interface TranslationAdapter {
  @NonNull String sourceIdentity ();

  @Nullable TdApi.FormattedText languageDetectionText ();

  default @NonNull String tone () {
    return "neutral";
  }

  void request (@NonNull Tdlib tdlib, @NonNull String targetLanguage,
                @NonNull String tone, @NonNull Client.ResultHandler callback);

  /**
   * Returns the typed translated value, or {@code null} for an unexpected
   * response constructor.
   */
  @Nullable TdApi.Object validate (@NonNull TdApi.Object response);
}
