/*
 * This file is a part of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

public final class SettingsBusinessController extends RecyclerViewController<Void>
    implements View.OnClickListener {
  private SettingsAdapter adapter;
  private boolean premium;

  public SettingsBusinessController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () { return R.id.controller_business; }

  @Override
  public CharSequence getName () { return Lang.getString(R.string.TelegramBusiness); }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this);
    recyclerView.setAdapter(adapter);
    buildCells();
  }

  private void buildCells () {
    premium = tdlib.hasPremium();
    adapter.setItems(new ListItem[] {
      new ListItem(ListItem.TYPE_DESCRIPTION_CENTERED, 0, 0,
        Lang.getMarkdownString(this, premium ? R.string.TelegramBusinessSubscribedSubtitle :
          R.string.TelegramBusinessSubtitle)),
      new ListItem(ListItem.TYPE_SHADOW_TOP),
      new ListItem(ListItem.TYPE_SETTING, R.id.btn_businessLocation,
        R.drawable.baseline_location_on_24, R.string.Location),
      new ListItem(ListItem.TYPE_SHADOW_BOTTOM),
      new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.BusinessLocationInfo)
    }, false);
  }

  @Override
  public void onFocus () {
    super.onFocus();
    if (adapter != null && premium != tdlib.hasPremium()) buildCells();
  }

  @Override
  public void onClick (View view) {
    if (view.getId() == R.id.btn_businessLocation) {
      if (tdlib.hasPremium()) {
        EditBusinessLocationController.open(this);
      } else {
        showConfirm(Lang.getMarkdownString(this, R.string.TelegramBusinessSubtitle),
          Lang.getString(R.string.BusinessSubscribe), this::openSubscription);
      }
    }
  }

  private void openSubscription () {
    tdlib.send(new TdApi.GetPremiumState(), (state, error) -> tdlib.uiExecute(() -> {
      if (isDestroyed()) return;
      if (error != null) {
        UI.showError(error);
        return;
      }
      for (TdApi.PremiumStatePaymentOption option : state.paymentOptions) {
        if (option.paymentOption.paymentLink != null) {
          tdlib.ui().openInternalLinkType(this, null, option.paymentOption.paymentLink, null, null);
          return;
        }
      }
      showOptions(Lang.getString(R.string.BusinessPremiumUnavailable),
        new int[] {R.id.btn_cancel}, new String[] {Lang.getString(R.string.OK)}, null, null);
    }));
  }
}
