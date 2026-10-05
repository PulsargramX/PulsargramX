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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.core.lambda.Destroyable;

public class ChatBoostHeaderView extends LinearLayout implements Destroyable {
  private final ChatBoostProgressView progress;
  private final ImageView emblem;
  private final TextView title, chatTitle, description, applied;
  private final LinearLayout chat;
  private final AvatarView avatar;

  public ChatBoostHeaderView (ViewController<?> controller, Runnable onClose) {
    super(controller.context());
    Context context = getContext();
    setOrientation(VERTICAL);
    setGravity(Gravity.CENTER_HORIZONTAL);
    setLayoutParams(new RecyclerView.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    setPadding(0, 0, 0, Screen.dp(20f));

    if (onClose != null) {
      FrameLayout top = new FrameLayout(context);
      View handle = new View(context) {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        protected void onDraw (Canvas canvas) {
          paint.setColor(Theme.getColor(ColorId.separator));
          canvas.drawRoundRect(0, 0, getWidth(), getHeight(), Screen.dp(2f), Screen.dp(2f), paint);
        }
      };
      FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(Screen.dp(32f), Screen.dp(4f),
        Gravity.CENTER);
      top.addView(handle, hp);
      controller.addThemeInvalidateListener(handle);
      ImageView close = new ImageView(context);
      close.setImageResource(R.drawable.baseline_close_20);
      close.setColorFilter(Theme.getColor(ColorId.icon));
      controller.addThemeFilterListener(close, ColorId.icon);
      close.setPadding(Screen.dp(12f), Screen.dp(12f), Screen.dp(12f), Screen.dp(12f));
      close.setContentDescription(Lang.getString(R.string.ChatBoostClose));
      close.setOnClickListener(v -> onClose.run());
      RippleSupport.setTransparentSelector(close, 22f, controller);
      FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Screen.dp(44f), Screen.dp(44f),
        Gravity.END | Gravity.CENTER_VERTICAL);
      cp.setMarginEnd(Screen.dp(4f));
      top.addView(close, cp);
      addView(top, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(36f)));
    }

    progress = new ChatBoostProgressView(context);
    addView(progress);
    controller.addThemeInvalidateListener(progress);

    emblem = new ImageView(context);
    emblem.setImageResource(R.drawable.baseline_flash_on_24);
    emblem.setColorFilter(Theme.getColor(ColorId.fillingPositiveContent));
    controller.addThemeFilterListener(emblem, ColorId.fillingPositiveContent);
    emblem.setBackground(new FillingDrawable(ColorId.fillingPositive, 32f));
    emblem.setPadding(Screen.dp(16f), Screen.dp(16f), Screen.dp(16f), Screen.dp(16f));
    LayoutParams ep = new LayoutParams(Screen.dp(64f), Screen.dp(64f));
    ep.topMargin = Screen.dp(24f);
    ep.bottomMargin = Screen.dp(8f);
    addView(emblem, ep);
    controller.addThemeInvalidateListener(emblem);

    title = text(controller, 22f, ColorId.text, true);
    title.setPadding(Screen.dp(24f), Screen.dp(16f), Screen.dp(24f), Screen.dp(10f));
    addView(title, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT));

    chat = new LinearLayout(context);
    chat.setOrientation(HORIZONTAL);
    chat.setGravity(Gravity.CENTER_VERTICAL);
    chat.setLayoutDirection(Lang.rtl() ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
    RippleSupport.setSimpleWhiteBackground(chat, ColorId.background, 18f, controller);
    chat.setPadding(Screen.dp(Lang.rtl() ? 12f : 4f), Screen.dp(4f),
      Screen.dp(Lang.rtl() ? 4f : 12f), Screen.dp(4f));
    avatar = new AvatarView(context);
    avatar.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    chat.addView(avatar, new LayoutParams(Screen.dp(28f), Screen.dp(28f)));
    chatTitle = text(controller, 14f, ColorId.text, false);
    chatTitle.setMaxLines(1);
    chatTitle.setEllipsize(TextUtils.TruncateAt.END);
    chatTitle.setMaxWidth(Math.max(Screen.dp(100f), Screen.currentWidth() - Screen.dp(112f)));
    LayoutParams tp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
      ViewGroup.LayoutParams.WRAP_CONTENT);
    tp.setMarginStart(Screen.dp(8f));
    chat.addView(chatTitle, tp);
    addView(chat, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
      ViewGroup.LayoutParams.WRAP_CONTENT));

    description = text(controller, 15f, ColorId.textLight, false);
    description.setPadding(Screen.dp(28f), Screen.dp(12f), Screen.dp(28f), 0);
    description.setLineSpacing(Screen.dp(2f), 1f);
    addView(description, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT));

    applied = text(controller, 14f, ColorId.textNeutral, true);
    applied.setPadding(Screen.dp(24f), Screen.dp(12f), Screen.dp(24f), 0);
    addView(applied, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT));
  }

  static TextView text (ViewController<?> controller, float size, int colorId, boolean medium) {
    TextView view = new TextView(controller.context());
    view.setTextSize(size);
    view.setTypeface(medium ? Fonts.getRobotoMedium() : Fonts.getRobotoRegular());
    view.setGravity(Gravity.CENTER);
    view.setTextColor(Theme.getColor(colorId));
    controller.addThemeTextColorListener(view, colorId);
    return view;
  }

  public void setChat (Tdlib tdlib, long chatId, TdApi.ChatBoostStatus status) {
    progress.setVisibility(status != null ? VISIBLE : GONE);
    if (status != null) {
      progress.setStatus(status);
    }
    emblem.setVisibility(status != null ? GONE : VISIBLE);
    title.setText(Lang.getString(tdlib.isChannel(chatId) ?
      R.string.ChatBoostChannel : R.string.ChatBoostGroup));
    chat.setVisibility(VISIBLE);
    avatar.setChat(tdlib, tdlib.chat(chatId));
    chatTitle.setText(tdlib.chatTitle(chatId));
    if (status == null) {
      applied.setVisibility(GONE);
      return;
    }
    description.setText(status.nextLevelBoostCount == 0 ?
      Lang.getString(R.string.ChatBoostMaxLevelInfo) :
      Lang.plural(R.string.ChatBoostUnlockNext,
        Math.max(0, status.nextLevelBoostCount - status.boostCount), tdlib.chatTitle(chatId)));
    applied.setVisibility(status.appliedSlotIds.length > 0 ? VISIBLE : GONE);
    applied.setText(Lang.plural(R.string.ChatBoostsApplied, status.appliedSlotIds.length));
  }

  public void setMessage (CharSequence message) {
    description.setText(message);
  }

  public void setPersonal (int total, int available) {
    progress.setVisibility(GONE);
    emblem.setVisibility(VISIBLE);
    chat.setVisibility(GONE);
    title.setText(Lang.getString(R.string.MyChatBoosts));
    description.setText(Lang.getString(R.string.ChatBoostMyInfo));
    applied.setVisibility(total > 0 ? VISIBLE : GONE);
    applied.setText(Lang.getString(R.string.ChatBoostSlotSummary,
      Lang.plural(R.string.ChatBoostCount, total),
      Lang.plural(R.string.ChatBoostAvailableCount, available)));
  }

  @Override
  public void performDestroy () {
    avatar.performDestroy();
  }
}
