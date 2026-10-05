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

import android.graphics.Canvas;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.FillingDrawable;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;

public class ChatBoostLevelView extends LinearLayout {
  private static final int[] ICONS = {
    R.drawable.baseline_link_24, R.drawable.baseline_palette_24,
    R.drawable.baseline_chat_bubble_24, R.drawable.baseline_premium_star_24,
    R.drawable.baseline_stars_24, R.drawable.baseline_camera_alt_24,
    R.drawable.baseline_palette_24, R.drawable.baseline_image_24,
    R.drawable.baseline_image_24, R.drawable.baseline_premium_star_24,
    R.drawable.baseline_stars_24, R.drawable.baseline_translate_24,
    R.drawable.baseline_mic_24, R.drawable.baseline_block_24
  };
  private final TextView heading;
  private final LinearLayout[] rows = new LinearLayout[ICONS.length];
  private final TextView[] labels = new TextView[ICONS.length];
  private final TextView empty;
  private boolean next;

  public ChatBoostLevelView (ViewController<?> controller) {
    super(controller.context());
    setOrientation(VERTICAL);
    setGravity(Gravity.CENTER_HORIZONTAL);
    setLayoutDirection(Lang.rtl() ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
    setPadding(Screen.dp(20f), Screen.dp(8f), Screen.dp(20f), Screen.dp(12f));
    setLayoutParams(new RecyclerView.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    heading = ChatBoostHeaderView.text(controller, 14f, ColorId.text, true);
    heading.setPadding(Screen.dp(16f), Screen.dp(7f), Screen.dp(16f), Screen.dp(7f));
    LayoutParams hp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
      ViewGroup.LayoutParams.WRAP_CONTENT);
    hp.bottomMargin = Screen.dp(14f);
    addView(heading, hp);
    controller.addThemeInvalidateListener(heading);

    for (int i = 0; i < ICONS.length; i++) {
      LinearLayout row = rows[i] = new LinearLayout(getContext());
      row.setOrientation(HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setMinimumHeight(Screen.dp(44f));
      row.setPadding(Screen.dp(6f), Screen.dp(8f), Screen.dp(6f), Screen.dp(8f));
      ImageView icon = new ImageView(getContext());
      icon.setImageResource(ICONS[i]);
      icon.setColorFilter(Theme.getColor(ColorId.textNeutral));
      icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      controller.addThemeFilterListener(icon, ColorId.textNeutral);
      row.addView(icon, new LayoutParams(Screen.dp(24f), Screen.dp(24f)));
      TextView label = labels[i] = ChatBoostHeaderView.text(controller, 15f, ColorId.text, false);
      label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
      lp.setMarginStart(Screen.dp(16f));
      row.addView(label, lp);
      addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    empty = ChatBoostHeaderView.text(controller, 14f, ColorId.textLight, false);
    empty.setText(Lang.getString(R.string.ChatBoostNoFeatures));
    addView(empty, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT));
  }

  public void setLevel (TdApi.ChatBoostLevelFeatures level, boolean next) {
    this.next = next;
    heading.setText(Lang.getString(next ? R.string.ChatBoostLevelUnlocks :
      R.string.ChatBoostLevelIncludes, level.level));
    heading.setBackground(next ? new FillingDrawable(ColorId.fillingPositive, 4f) : null);
    heading.setTextColor(Theme.getColor(next ? ColorId.fillingPositiveContent : ColorId.text));
    CharSequence[] text = {
      level.canSetBackgroundCustomEmoji ? Lang.getString(R.string.ChatBoostReplyEmoji) : null,
      level.accentColorCount > 0 ?
        Lang.plural(R.string.ChatBoostReplyColors, level.accentColorCount) : null,
      level.titleColorCount > 0 ?
        Lang.plural(R.string.ChatBoostTitleColors, level.titleColorCount) : null,
      level.canSetProfileBackgroundCustomEmoji ?
        Lang.getString(R.string.ChatBoostProfileEmoji) : null,
      level.customEmojiReactionCount > 0 ?
        Lang.plural(R.string.ChatBoostReactions, level.customEmojiReactionCount) : null,
      level.storyPerDayCount > 0 ?
        Lang.plural(R.string.ChatBoostStories, level.storyPerDayCount) : null,
      level.profileAccentColorCount > 0 ?
        Lang.plural(R.string.ChatBoostProfileColors, level.profileAccentColorCount) : null,
      level.chatThemeBackgroundCount > 0 ?
        Lang.plural(R.string.ChatBoostBackgrounds, level.chatThemeBackgroundCount) : null,
      level.canSetCustomBackground ? Lang.getString(R.string.ChatBoostCustomBackground) : null,
      level.canSetEmojiStatus ? Lang.getString(R.string.ChatBoostEmojiStatus) : null,
      level.canSetCustomEmojiStickerSet ? Lang.getString(R.string.ChatBoostEmojiSet) : null,
      level.canEnableAutomaticTranslation ? Lang.getString(R.string.ChatBoostTranslation) : null,
      level.canRecognizeSpeech ? Lang.getString(R.string.ChatBoostSpeechRecognition) : null,
      level.canDisableSponsoredMessages ? Lang.getString(R.string.ChatBoostDisableAds) : null
    };
    boolean hasFeatures = false;
    for (int i = 0; i < text.length; i++) {
      rows[i].setVisibility(text[i] != null ? VISIBLE : GONE);
      labels[i].setText(text[i]);
      hasFeatures |= text[i] != null;
    }
    empty.setVisibility(hasFeatures ? GONE : VISIBLE);
  }

  @Override
  protected void dispatchDraw (Canvas canvas) {
    heading.setTextColor(Theme.getColor(next ? ColorId.fillingPositiveContent : ColorId.text));
    super.dispatchDraw(canvas);
  }
}
