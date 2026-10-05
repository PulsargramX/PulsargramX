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
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;

public class ChatBoostLinkView extends LinearLayout {
  private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final LinearLayout linkRow;
  private final TextView link;

  public ChatBoostLinkView (ViewController<?> controller, View.OnClickListener listener) {
    super(controller.context());
    setOrientation(VERTICAL);
    setPadding(Screen.dp(20f), Screen.dp(8f), Screen.dp(20f), Screen.dp(16f));
    setLayoutParams(new RecyclerView.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    setLayoutDirection(Lang.rtl() ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
    border.setStyle(Paint.Style.STROKE);
    controller.addThemeInvalidateListener(this);

    linkRow = new LinearLayout(getContext());
    linkRow.setOrientation(HORIZONTAL);
    linkRow.setGravity(Gravity.CENTER_VERTICAL);
    linkRow.setMinimumHeight(Screen.dp(52f));
    linkRow.setPadding(Screen.dp(16f), Screen.dp(12f), Screen.dp(16f), Screen.dp(12f));
    linkRow.setId(R.id.btn_copyLink);
    linkRow.setOnClickListener(listener);
    RippleSupport.setTransparentSelector(linkRow, 6f, controller);
    ImageView icon = new ImageView(getContext());
    icon.setImageResource(R.drawable.baseline_link_24);
    icon.setColorFilter(Theme.getColor(ColorId.icon));
    icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    controller.addThemeFilterListener(icon, ColorId.icon);
    linkRow.addView(icon, new LayoutParams(Screen.dp(24f), Screen.dp(24f)));
    link = ChatBoostHeaderView.text(controller, 15f, ColorId.textLink, false);
    link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    link.setSingleLine();
    link.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    link.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    lp.setMarginStart(Screen.dp(12f));
    linkRow.addView(link, lp);
    addView(linkRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT));
    addView(divider(controller), new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
      Screen.dp(1f)));

    LinearLayout actions = new LinearLayout(getContext());
    actions.setOrientation(HORIZONTAL);
    actions.setBaselineAligned(false);
    int[] ids = {R.id.btn_copyLink, R.id.btn_share};
    int[] strings = {R.string.CopyLink, R.string.Share};
    for (int i = 0; i < ids.length; i++) {
      if (i > 0) {
        actions.addView(divider(controller), new LayoutParams(Screen.dp(1f),
          ViewGroup.LayoutParams.MATCH_PARENT));
      }
      TextView button = ChatBoostHeaderView.text(controller, 14f, ColorId.textNeutral, true);
      button.setGravity(Gravity.CENTER);
      button.setMinimumHeight(Screen.dp(48f));
      button.setPadding(Screen.dp(12f), Screen.dp(12f), Screen.dp(12f), Screen.dp(12f));
      button.setText(Lang.getString(strings[i]));
      button.setId(ids[i]);
      button.setOnClickListener(listener);
      RippleSupport.setTransparentSelector(button, 6f, controller);
      actions.addView(button, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }
    addView(actions, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT));
  }

  private static View divider (ViewController<?> controller) {
    View view = new View(controller.context());
    ViewSupport.setThemedBackground(view, ColorId.inputInactive, controller);
    return view;
  }

  @Override
  protected void dispatchDraw (Canvas canvas) {
    super.dispatchDraw(canvas);
    border.setColor(Theme.getColor(ColorId.inputInactive));
    border.setStrokeWidth(Screen.dp(1f));
    float inset = border.getStrokeWidth() / 2f;
    canvas.drawRoundRect(getPaddingLeft() + inset, getPaddingTop() + inset,
      getWidth() - getPaddingRight() - inset, getHeight() - getPaddingBottom() - inset,
      Screen.dp(6f), Screen.dp(6f), border);
  }

  public void setLink (String url) {
    link.setText(url.startsWith("https://") ? url.substring(8) : url);
    linkRow.setContentDescription(Lang.getString(R.string.ChatBoostCopyLink, url));
  }
}
