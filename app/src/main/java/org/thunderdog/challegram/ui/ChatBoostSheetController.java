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

import android.content.Context;
import android.graphics.Outline;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;

import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.widget.PopupLayout;
import org.thunderdog.challegram.widget.ViewPager;

import me.vkryl.android.widget.FrameLayoutFix;

public class ChatBoostSheetController extends
    SinglePageBottomSheetViewController<ChatBoostController, ChatBoostController.Args> {
  public ChatBoostSheetController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  protected ChatBoostController onCreateSinglePage () {
    return new ChatBoostController(this);
  }

  @Override
  public boolean supportsBottomInset () {
    return true;
  }

  @Override
  protected int getBackgroundColorId () {
    return ColorId.filling;
  }

  @Override
  protected int getHeaderHeight () {
    return 0;
  }

  @Override
  protected HeaderView onCreateHeaderView () {
    return null;
  }

  @Override
  protected int getContentOffset () {
    int available = getTargetHeight() - HeaderView.getTopOffset();
    return Math.max(0, Math.min(available / 4, available - Screen.dp(520f)));
  }

  @Override
  protected boolean canHideByScroll () {
    return true;
  }

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, ViewPager pager) {
    super.onCreateView(context, contentView, pager);
    tdlib.ui().post(this::launchOpenAnimation);
  }

  @Override
  protected void setupPopupLayout (PopupLayout popupLayout) {
    super.setupPopupLayout(popupLayout);
    popupLayout.setBoundController(this);
  }

  boolean isShowing () {
    return getPopupLayout() != null && getPopupLayout().isBoundWindowShowing();
  }

  @Override
  protected void onCustomShowComplete () {
    super.onCustomShowComplete();
    singlePage.onSheetShown();
  }

  @Override
  public boolean needsTempUpdates () {
    return true;
  }

  @Override
  protected void onAfterCreateView () {
    setLickViewColor(Theme.fillingColor());
    fixView.setVisibility(View.GONE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      contentView.setOutlineProvider(new ViewOutlineProvider() {
        @Override
        public void getOutline (View view, Outline outline) {
          int radius = Math.min(Screen.dp(20f), getTopEdge());
          if (radius == 0) {
            outline.setRect(0, (int) headerTranslationY, view.getWidth(), view.getHeight());
          } else {
            outline.setRoundRect(0, (int) headerTranslationY, view.getWidth(),
              view.getHeight() + radius, radius);
          }
        }
      });
      contentView.setClipToOutline(true);
    }
  }

  @Override
  protected void setHeaderPosition (float y) {
    super.setHeaderPosition(y);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      contentView.invalidateOutline();
    }
  }

  @Override
  public void onThemeColorsChanged (boolean areTemp, ColorState state) {
    super.onThemeColorsChanged(areTemp, state);
    setLickViewColor(Theme.fillingColor());
  }
}
