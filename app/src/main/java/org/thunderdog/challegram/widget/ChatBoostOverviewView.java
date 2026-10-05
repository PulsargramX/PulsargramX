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
package org.thunderdog.challegram.widget;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;

public class ChatBoostOverviewView extends LinearLayout {
  private final TextView[] values = new TextView[4];

  public ChatBoostOverviewView (ViewController<?> controller) {
    super(controller.context());
    setOrientation(VERTICAL);
    setPadding(Screen.dp(20f), Screen.dp(4f), Screen.dp(20f), Screen.dp(12f));
    setLayoutParams(new RecyclerView.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    int[] titles = {R.string.ChatBoostTotal, R.string.ChatBoostCurrentLevel,
      R.string.ChatBoostGiftCodes, R.string.ChatBoostPremiumMembers};
    for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
      LinearLayout row = new LinearLayout(getContext());
      row.setOrientation(HORIZONTAL);
      row.setLayoutDirection(Lang.rtl() ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
      for (int column = 0; column < 2; column++) {
        int index = rowIndex * 2 + column;
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(VERTICAL);
        cell.setPadding(Screen.dp(4f), Screen.dp(12f), Screen.dp(4f), Screen.dp(12f));
        TextView value = values[index] =
          ChatBoostHeaderView.text(controller, 20f, ColorId.text, true);
        value.setGravity(Gravity.START);
        cell.addView(value);
        TextView label = ChatBoostHeaderView.text(controller, 13f, ColorId.textLight, false);
        label.setGravity(Gravity.START);
        label.setPadding(0, Screen.dp(4f), 0, 0);
        label.setText(Lang.getString(titles[index]));
        cell.addView(label);
        row.addView(cell, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      }
      addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT));
    }
  }

  public void setStatus (TdApi.ChatBoostStatus status) {
    values[0].setText(Strings.buildCounter(status.boostCount));
    values[1].setText(Strings.buildCounter(status.level));
    values[2].setText(Strings.buildCounter(status.giftCodeBoostCount));
    values[3].setText(Lang.getString(R.string.ChatBoostPremiumAudience,
      Strings.buildCounter(status.premiumMemberCount),
      Lang.beautifyDouble(status.premiumMemberPercentage)));
  }
}
