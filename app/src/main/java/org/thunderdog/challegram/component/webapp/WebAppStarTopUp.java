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
import android.app.AlertDialog;
import android.content.Context;
import android.webkit.WebView;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;

import java.util.ArrayList;
import java.util.function.Consumer;

import me.vkryl.android.AppInstallationUtil;

/** A separate purchase never authorizes paying the original invoice. */
final class WebAppStarTopUp {
  private final WebAppActions owner;
  private final Consumer<String> completed;
  private WebAppActions child;
  private boolean active = true;

  WebAppStarTopUp (WebAppActions owner, Consumer<String> completed) {
    this.owner = owner;
    this.completed = completed;
  }
  private boolean alive () { return active && owner.alive(); }
  void open (long chatId) {
    if (!alive()) return;
    if (!AppInstallationUtil.isAppSideLoaded(owner.host.context())) {
      owner.show(new AlertDialog.Builder(owner.host.activity()).setTitle(R.string.WebAppStarsTopUpTitle)
        .setMessage(R.string.WebAppStarsTopUpStoreUnavailable)
        .setPositiveButton(android.R.string.ok, (d, w) -> finish("failed")), () -> finish("failed"));
      return;
    }
    owner.send(new TdApi.GetStarPaymentOptions(), result -> {
      if (!alive()) return;
      if (!(result instanceof TdApi.StarPaymentOptions)) { owner.error(result); finish("failed"); return; }
      ArrayList<TdApi.StarPaymentOption> options = new ArrayList<>();
      ArrayList<String> titles = new ArrayList<>();
      for (TdApi.StarPaymentOption option : ((TdApi.StarPaymentOptions) result).options) {
        if (option.starCount <= 0 || option.amount <= 0 || option.currency == null || option.currency.isEmpty()) continue;
        options.add(option);
        titles.add(owner.host.context().getString(R.string.WebAppStarsTopUpOption, option.starCount,
          WebAppPayments.amount(option.amount, option.currency)));
      }
      if (options.isEmpty()) { finish("failed"); return; }
      owner.show(new AlertDialog.Builder(owner.host.activity()).setTitle(R.string.WebAppStarsTopUpTitle)
        .setItems(titles.toArray(new String[0]), (d, which) -> {
          if (!alive()) return;
          TdApi.StarPaymentOption option = options.get(which);
          // The native option selection and checkout Pay button provide fresh consent.
          child = WebAppActions.openInvoice(new ChildHost(), new TdApi.InputInvoiceTelegram(
            new TdApi.TelegramPaymentPurposeStars(option.currency, option.amount, option.starCount, chatId)));
          if (!active) child.destroy();
        }).setNegativeButton(android.R.string.cancel, (d, w) -> finish("cancelled")), () -> finish("cancelled"));
    });
  }
  private void finish (String status) {
    if (!active) return;
    active = false;
    if (child != null) { child.destroy(); child = null; }
    if (owner.alive()) completed.accept(status);
  }
  void destroy () {
    active = false;
    if (child != null) { child.destroy(); child = null; }
  }

  private final class ChildHost implements WebAppActions.Host {
    @Override public Context context () { return owner.host.context(); }
    @Override public Activity activity () { return owner.host.activity(); }
    @Override public Tdlib tdlib () { return owner.host.tdlib(); }
    @Override public WebAppSession session () { return owner.host.session(); }
    @Override public ViewController<?> controller () { return owner.host.controller(); }
    @Override public WebView webView () { return null; }
    @Override public boolean isAlive () { return alive(); }
    @Override public boolean hasRecentUserGesture () { return alive(); }
    @Override public void emit (String event, JSONObject data) {
      if ("invoice_closed".equals(event)) finish(data.optString("status", "failed"));
    }
    @Override public void close (boolean force) { finish("cancelled"); }
    @Override public void openExternalUrl (String url) { if (alive()) owner.host.openExternalUrl(url); }
    @Override public void runOnUiThread (Runnable runnable) { org.thunderdog.challegram.tool.UI.post(runnable); }
  }
}
