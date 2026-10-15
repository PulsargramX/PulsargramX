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
package org.thunderdog.challegram.ui;

import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import org.thunderdog.challegram.navigation.ViewPagerHeaderViewCompact;
import org.thunderdog.challegram.navigation.ViewPagerTopView;
import org.thunderdog.challegram.telegram.ChatFolderStyle;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.Counter;

import me.vkryl.core.lambda.FutureBool;

final class ChatFolderTab {
  private ChatFolderTab () { }

  static boolean hasIcon (@ChatFolderStyle int style) {
    switch (style) {
      case ChatFolderStyle.ICON_ONLY:
      case ChatFolderStyle.LABEL_AND_ICON:
      case ChatFolderStyle.ICON_WITH_LABEL_ON_ACTIVE_FOLDER:
        return true;
      case ChatFolderStyle.LABEL_ONLY:
        return false;
      default:
        throw new IllegalArgumentException("style = " + style);
    }
  }

  static boolean hasLabel (@ChatFolderStyle int style, boolean firstMainTab) {
    return style != ChatFolderStyle.ICON_ONLY && !firstMainTab;
  }

  static ViewPagerTopView.Item build (CharSequence label, int icon,
    @ChatFolderStyle int style, boolean firstMainTab, @Nullable Counter counter) {
    if (!hasIcon(style) && !firstMainTab) {
      icon = ResourcesCompat.ID_NULL;
    }
    ViewPagerTopView.Item item = new ViewPagerTopView.Item(
      TextUtils.isEmpty(label) ? null : label, icon, counter
    );
    item.setMinWidth(Screen.dp(56f - ViewPagerTopView.COMPACT_ITEM_PADDING * 2));
    return item;
  }

  static Counter buildCounter (FutureBool tabsAtBottom, Counter.Callback callback) {
    MainController.UnreadCounterColorSet colors =
      new MainController.UnreadCounterColorSet(tabsAtBottom);
    Counter counter = new Counter.Builder()
      .textSize(12f)
      .backgroundPadding(4f)
      .outlineAffectsBackgroundSize(false)
      .colorSet(colors)
      .callback(callback)
      .build();
    colors.setCounter(counter);
    return counter;
  }

  static void configure (ViewPagerHeaderViewCompact header, @ChatFolderStyle int style,
    boolean hasFolders, boolean tabsAtBottom) {
    ViewPagerTopView top = header.getTopView();
    top.setAnimateItemChanges(!Settings.instance().needReduceMotion());
    top.setShowLabelOnActiveOnly(hasFolders &&
      style == ChatFolderStyle.ICON_WITH_LABEL_ON_ACTIVE_FOLDER);
    top.setUseDarkBackground(tabsAtBottom);
    top.setDrawSelectionAtTop(tabsAtBottom);
    top.setSlideOffDirection(tabsAtBottom ?
      ViewPagerTopView.SLIDE_OFF_DIRECTION_TOP : ViewPagerTopView.SLIDE_OFF_DIRECTION_BOTTOM);
    top.setItemPadding(Screen.dp(hasFolders ?
      ViewPagerTopView.COMPACT_ITEM_PADDING : ViewPagerTopView.DEFAULT_ITEM_PADDING));
    top.setItemSpacing(Screen.dp(hasFolders ?
      ViewPagerTopView.COMPACT_ITEM_SPACING : ViewPagerTopView.DEFAULT_ITEM_SPACING));
    header.setFadingEdgeLength(tabsAtBottom ? 0f : 16f);
  }
}
