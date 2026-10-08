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

import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;

import java.util.concurrent.TimeUnit;

import me.vkryl.core.lambda.Destroyable;

public class ChatBoostSlotView extends LinearLayout implements Destroyable {
  private final AvatarView avatar;
  private final ImageView state;
  private final CheckBoxView checkbox;
  private final TextView title, detail;
  private boolean selected;

  public ChatBoostSlotView (ViewController<?> controller) {
    super(controller.context());
    setOrientation(HORIZONTAL);
    setGravity(Gravity.CENTER_VERTICAL);
    setLayoutDirection(Lang.rtl() ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
    setMinimumHeight(Screen.dp(80f));
    setPadding(Screen.dp(20f), Screen.dp(12f), Screen.dp(20f), Screen.dp(12f));
    setLayoutParams(new RecyclerView.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    RippleSupport.setSimpleWhiteBackground(this, controller);
    avatar = new AvatarView(getContext());
    avatar.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(avatar, new LayoutParams(Screen.dp(44f), Screen.dp(44f)));

    LinearLayout text = new LinearLayout(getContext());
    text.setOrientation(VERTICAL);
    title = ChatBoostHeaderView.text(controller, 16f, ColorId.text, false);
    title.setGravity(Gravity.START);
    title.setMaxLines(1);
    title.setEllipsize(TextUtils.TruncateAt.END);
    text.addView(title);
    detail = ChatBoostHeaderView.text(controller, 13f, ColorId.textLight, false);
    detail.setGravity(Gravity.START);
    detail.setPadding(0, Screen.dp(4f), 0, 0);
    text.addView(detail);
    LayoutParams tp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    tp.setMarginStart(Screen.dp(14f));
    tp.setMarginEnd(Screen.dp(8f));
    addView(text, tp);

    checkbox = new CheckBoxView(getContext());
    checkbox.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(checkbox, new LayoutParams(Screen.dp(24f), Screen.dp(24f)));
    controller.addThemeInvalidateListener(checkbox);
    state = new ImageView(getContext());
    state.setColorFilter(Theme.getColor(ColorId.textLight));
    state.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    controller.addThemeFilterListener(state, ColorId.textLight);
    addView(state, new LayoutParams(Screen.dp(24f), Screen.dp(24f)));
  }

  public void setSlot (Tdlib tdlib, TdApi.ChatBoostSlot slot,
                       boolean selected, boolean usable, boolean busy) {
    this.selected = selected;
    boolean cooling = slot.cooldownUntilDate > tdlib.currentTime(TimeUnit.SECONDS);
    avatar.setChat(tdlib, tdlib.chat(slot.currentlyBoostedChatId));
    title.setText(tdlib.chatTitle(slot.currentlyBoostedChatId));
    String expiry = Lang.getString(R.string.ChatBoostExpires,
      Lang.getDate(slot.expirationDate, TimeUnit.SECONDS));
    CharSequence subtitle = cooling ? Lang.getString(R.string.ChatBoostAvailableFrom,
        Lang.getTimestamp(slot.cooldownUntilDate, TimeUnit.SECONDS)) :
      Lang.getString(R.string.ChatBoostCanReassign);
    detail.setText(subtitle + "\n" + expiry);
    checkbox.setVisibility(usable ? VISIBLE : GONE);
    checkbox.setChecked(selected, false);
    state.setVisibility(cooling ? VISIBLE : GONE);
    state.setImageResource(R.drawable.baseline_schedule_24);
    setEnabled(!busy && usable);
    setContentDescription(title.getText() + ", " + detail.getText());
  }

  @Override
  public void onInitializeAccessibilityNodeInfo (AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    if (checkbox.getVisibility() == VISIBLE) {
      info.setClassName(android.widget.CheckBox.class.getName());
      info.setCheckable(true);
      info.setChecked(selected);
    }
  }

  @Override
  public void performDestroy () {
    avatar.performDestroy();
  }
}
