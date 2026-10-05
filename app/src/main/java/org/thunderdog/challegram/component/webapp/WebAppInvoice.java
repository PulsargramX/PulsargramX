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

import android.app.Activity;
import android.content.Context;
import android.webkit.WebView;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.GlobalAccountListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibAccount;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.UI;

import me.vkryl.core.lambda.Destroyable;

/** Reuses native checkout for invoice links and message Buy buttons. */
public final class WebAppInvoice implements WebAppActions.Host, Destroyable, GlobalAccountListener,
    org.thunderdog.challegram.BaseActivity.PasscodeListener {
  private final ViewController<?> owner;
  private final long userId;
  private final Runnable onFinished;
  private WebAppActions actions;
  private boolean destroyed;

  private WebAppInvoice (ViewController<?> owner, Runnable onFinished) {
    this.owner = owner;
    this.userId = owner.tdlib().myUserId();
    this.onFinished = onFinished;
    owner.addDestroyListener(this);
    owner.context().addPasscodeListener(this);
    owner.tdlib().context().global().addAccountListener(this);
  }

  public static void open (ViewController<?> owner, TdApi.InputInvoice invoice, Runnable onFinished) {
    if (owner == null || owner.isDestroyed() || !owner.tdlib().isAuthorized()) {
      if (onFinished != null) onFinished.run();
      return;
    }
    WebAppInvoice host = new WebAppInvoice(owner, onFinished);
    host.actions = WebAppActions.openInvoice(host, invoice);
    if (host.destroyed) host.actions.destroy();
  }

  @Override public Context context () { return owner.context(); }
  @Override public Activity activity () { return owner.context(); }
  @Override public Tdlib tdlib () { return owner.tdlib(); }
  @Override public WebAppSession session () { return null; }
  @Override public ViewController<?> controller () { return owner; }
  @Override public WebView webView () { return null; }
  @Override public boolean isAlive () {
    return !destroyed && !owner.isDestroyed() && tdlib().isCurrent() &&
      tdlib().isAuthorized() && tdlib().myUserId() == userId && !owner.context().isPasscodeShowing();
  }
  @Override public boolean hasRecentUserGesture () { return isAlive(); }
  @Override public void emit (String event, JSONObject data) {
    if ("invoice_closed".equals(event)) destroy();
  }
  @Override public void close (boolean force) { destroy(); }
  @Override public void openExternalUrl (String url) {
    if (isAlive()) Intents.openUri(url);
  }
  @Override public void runOnUiThread (Runnable runnable) { UI.post(runnable); }
  @Override public void onAccountSwitched (TdlibAccount account, TdApi.User profile, int reason,
                                          TdlibAccount oldAccount) {
    if (account.id != tdlib().id()) UI.post(this::destroy);
  }
  @Override public void onAuthorizationStateChanged (TdlibAccount account,
      TdApi.AuthorizationState state, int status) {
    if (account.id == tdlib().id() && !(state instanceof TdApi.AuthorizationStateReady)) {
      UI.post(this::destroy);
    }
  }
  @Override public void onPasscodeShowing (org.thunderdog.challegram.BaseActivity activity,
                                          boolean showing) {
    if (showing) destroy();
  }
  @Override public void performDestroy () { destroy(); }

  public void destroy () {
    if (destroyed) return;
    destroyed = true;
    owner.removeDestroyListener(this);
    owner.context().removePasscodeListener(this);
    tdlib().context().global().removeAccountListener(this);
    if (actions != null) actions.destroy();
    if (onFinished != null) onFinished.run();
  }
}
