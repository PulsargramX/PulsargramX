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
import org.thunderdog.challegram.FillingDrawable;
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
  private final ImageView available, state;
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
    available = new ImageView(getContext());
    available.setImageResource(R.drawable.baseline_flash_on_24);
    available.setColorFilter(Theme.getColor(ColorId.fillingPositiveContent));
    available.setBackground(new FillingDrawable(ColorId.fillingPositive, 22f));
    available.setPadding(Screen.dp(10f), Screen.dp(10f), Screen.dp(10f), Screen.dp(10f));
    available.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    controller.addThemeFilterListener(available, ColorId.fillingPositiveContent);
    controller.addThemeInvalidateListener(available);
    addView(available, new LayoutParams(Screen.dp(44f), Screen.dp(44f)));

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

  public void setSlot (Tdlib tdlib, TdApi.ChatBoostSlot slot, long targetChatId,
                       boolean selected, boolean usable, boolean busy) {
    this.selected = selected;
    boolean assigned = slot.currentlyBoostedChatId != 0;
    boolean applied = targetChatId != 0 && slot.currentlyBoostedChatId == targetChatId;
    boolean cooling = slot.cooldownUntilDate > tdlib.currentTime(TimeUnit.SECONDS);
    avatar.setVisibility(assigned ? VISIBLE : GONE);
    avatar.setChat(tdlib, assigned ? tdlib.chat(slot.currentlyBoostedChatId) : null);
    available.setVisibility(assigned ? GONE : VISIBLE);
    title.setText(assigned ? tdlib.chatTitle(slot.currentlyBoostedChatId) :
      Lang.getString(R.string.ChatBoostUnused));
    String expiry = Lang.getString(R.string.ChatBoostExpires,
      Lang.getDate(slot.expirationDate, TimeUnit.SECONDS));
    CharSequence subtitle = applied ? Lang.getString(R.string.ChatBoostAlreadyApplied) :
      cooling ? Lang.getString(R.string.ChatBoostAvailableFrom,
        Lang.getTimestamp(slot.cooldownUntilDate, TimeUnit.SECONDS)) :
      assigned && targetChatId != 0 ? Lang.getString(R.string.ChatBoostCanReassign) : null;
    detail.setText(subtitle == null ? expiry : subtitle + "\n" + expiry);
    checkbox.setVisibility(targetChatId != 0 && usable ? VISIBLE : GONE);
    checkbox.setChecked(selected, false);
    state.setVisibility((targetChatId == 0 && assigned) || applied || cooling ? VISIBLE : GONE);
    state.setImageResource(applied ? R.drawable.baseline_check_circle_24 :
      cooling ? R.drawable.baseline_schedule_24 : R.drawable.baseline_arrow_forward_24);
    setEnabled(!busy && (targetChatId == 0 ? assigned : usable));
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
