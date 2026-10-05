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
 *
 */
package org.thunderdog.challegram.component.webapp;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;

import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one TDLib launch and its reference independently of the screen displaying it. */
public final class WebAppSession {
  public final Tdlib tdlib;
  public final WebAppLaunchRequest request;
  public final String url;
  public final boolean requireSameOrigin;
  public final long launchId;
  public final TdApi.WebAppOpenMode mode;
  public final long userId;

  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean dataSent = new AtomicBoolean();
  private final AtomicBoolean ageCompleted = new AtomicBoolean();
  private final Runnable onClosed;
  private @Nullable Runnable closeListener;

  public WebAppSession (Tdlib tdlib, WebAppLaunchRequest request, TdApi.WebAppUrl url,
                        long launchId, TdApi.WebAppOpenMode mode, Runnable onClosed) {
    this.tdlib = tdlib;
    this.request = new WebAppLaunchRequest(request);
    this.url = url.url;
    this.requireSameOrigin = url.requireSameOrigin ||
      request.source == WebAppLaunchRequest.Source.AGE_VERIFICATION;
    this.launchId = launchId;
    this.mode = mode;
    this.userId = tdlib.myUserId();
    this.onClosed = onClosed;
    tdlib.incrementUiReferenceCount();
  }

  public boolean isClosed () {
    return closed.get() || tdlib.myUserId() != userId || !tdlib.isAuthorized();
  }

  public void setCloseListener (@Nullable Runnable listener) {
    closeListener = listener;
    if (listener != null && closed.get()) {
      UI.post(listener);
    }
  }

  public void close () {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (launchId != 0 && tdlib.isAuthorized() && tdlib.myUserId() == userId) {
      tdlib.send(new TdApi.CloseWebApp(launchId), (result, error) -> { });
    }
    UI.post(() -> {
      tdlib.decrementUiReferenceCount();
      onClosed.run();
      Runnable listener = closeListener;
      closeListener = null;
      if (listener != null) {
        listener.run();
      }
      if (ageCompleted.compareAndSet(false, true) && request.onAgeVerified != null) {
        request.onAgeVerified.accept(false);
      }
    });
  }

  public void verifyAge (org.json.JSONObject data) {
    if (isClosed() || request.source != WebAppLaunchRequest.Source.AGE_VERIFICATION ||
        request.onAgeVerified == null) return;
    TdApi.AgeVerificationParameters parameters = tdlib.ageVerificationParameters();
    if (parameters == null || !parameters.verificationBotUsername.equalsIgnoreCase(request.botUsername)) return;
    double age = data.optDouble("age", Double.NaN);
    if (Double.isNaN(age) || Double.isInfinite(age) || age < 0 || age > 150 ||
        !ageCompleted.compareAndSet(false, true)) return;
    boolean passed = age >= parameters.minAge;
    close();
    UI.post(() -> {
      if (tdlib.isAuthorized() && tdlib.myUserId() == userId) request.onAgeVerified.accept(passed);
    });
  }

  public void sendData (String data) {
    if (isClosed() || request.source != WebAppLaunchRequest.Source.KEYBOARD || data == null ||
        data.isEmpty() || data.getBytes(Charset.forName("UTF-8")).length > 4096 ||
        !dataSent.compareAndSet(false, true)) {
      return;
    }
    tdlib.send(new TdApi.SendWebAppData(request.botUserId, request.buttonText, data),
      (result, error) -> {
        if (error != null) {
          UI.showError(error);
        }
      });
    close();
  }
}
