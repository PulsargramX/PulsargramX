/*
 * This file is a part of Pulsargram X, based on Telegram X.
 * Copyright © 2026 Pulsargram X contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.component.webapp;

import android.graphics.Color;

import androidx.core.graphics.ColorUtils;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;

/** Keeps the launch parameters and live JavaScript theme updates identical. */
public final class WebAppTheme {
  private WebAppTheme () { }

  public static int backgroundColor () { return Theme.backgroundColor(); }

  public static int sectionColor () {
    int section = Theme.fillingColor();
    if (section == backgroundColor()) {
      section = ColorUtils.blendARGB(section, Theme.isDark() ? Color.WHITE : Color.BLACK, .06f);
    }
    return section;
  }

  public static TdApi.ThemeParameters parameters () {
    int background = backgroundColor() & 0xffffff;
    return new TdApi.ThemeParameters(
      background, background, background, background, sectionColor() & 0xffffff,
      Theme.getColor(ColorId.separator) & 0xffffff, Theme.getColor(ColorId.text) & 0xffffff,
      Theme.getColor(ColorId.textLink) & 0xffffff, Theme.getColor(ColorId.textLink) & 0xffffff,
      Theme.getColor(ColorId.textLight) & 0xffffff, Theme.getColor(ColorId.textNegative) & 0xffffff,
      Theme.getColor(ColorId.textLight) & 0xffffff, Theme.getColor(ColorId.textLink) & 0xffffff,
      Theme.getColor(ColorId.fillingPositive) & 0xffffff,
      Theme.getColor(ColorId.fillingPositiveContent) & 0xffffff);
  }

  public static JSONObject json () {
    TdApi.ThemeParameters p = parameters();
    return WebAppDevice.object(
      "bg_color", hex(p.backgroundColor), "secondary_bg_color", hex(p.secondaryBackgroundColor),
      "header_bg_color", hex(p.headerBackgroundColor), "bottom_bar_bg_color", hex(p.bottomBarBackgroundColor),
      "section_bg_color", hex(p.sectionBackgroundColor), "section_separator_color", hex(p.sectionSeparatorColor),
      "text_color", hex(p.textColor), "accent_text_color", hex(p.accentTextColor),
      "section_header_text_color", hex(p.sectionHeaderTextColor), "subtitle_text_color", hex(p.subtitleTextColor),
      "destructive_text_color", hex(p.destructiveTextColor), "hint_color", hex(p.hintColor),
      "link_color", hex(p.linkColor), "button_color", hex(p.buttonColor), "button_text_color", hex(p.buttonTextColor));
  }

  private static String hex (int color) {
    return String.format(java.util.Locale.US, "#%06x", color & 0xffffff);
  }
}
