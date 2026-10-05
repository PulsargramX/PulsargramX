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
 *
 */
package org.thunderdog.challegram.component.webapp;

import android.animation.ValueAnimator;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.ViewUtils;

import java.util.function.Consumer;

/** Restores retained WebViews without launching a new authenticated session. */
public final class WebAppTabsView extends HorizontalScrollView {
  public static final int HEIGHT_DP = 56;
  private final BaseActivity activity;
  private final LinearLayout tabs;
  private final Runnable visibilityChanged;
  private final Consumer<Float> revealChanged;
  private final Runnable appsChanged = this::refresh;
  private @Nullable Tdlib tdlib;
  private boolean hasApps;
  private float revealFactor, targetFactor;
  private int keyboardInset;
  private ValueAnimator revealAnimator;

  public WebAppTabsView (BaseActivity activity, Runnable visibilityChanged, Consumer<Float> revealChanged) {
    super(activity);
    this.activity = activity;
    this.visibilityChanged = visibilityChanged;
    this.revealChanged = revealChanged;
    setHorizontalScrollBarEnabled(false);
    setFillViewport(true);
    setOverScrollMode(OVER_SCROLL_NEVER);
    setPadding(Screen.dp(8), Screen.dp(4), Screen.dp(8), Screen.dp(4));
    setVisibility(View.GONE);
    tabs = new LinearLayout(activity);
    tabs.setOrientation(LinearLayout.HORIZONTAL);
    tabs.setGravity(Gravity.CENTER_VERTICAL);
    addView(tabs, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
  }

  public void setTdlib (@Nullable Tdlib account) {
    if (tdlib == account) return;
    if (tdlib != null) tdlib.webApps().removeListener(appsChanged);
    tdlib = account;
    if (tdlib != null) tdlib.webApps().addListener(appsChanged);
    refresh();
  }

  public boolean hasApps () {
    return hasApps;
  }

  public void setKeyboardInset (int inset) {
    if (keyboardInset == inset) return;
    keyboardInset = inset;
    updateOccupiedHeight();
  }

  public void setRevealFactor (float target, boolean animated) {
    target = Math.max(0f, Math.min(1f, target));
    if (target == targetFactor && (animated || target == revealFactor)) return;
    targetFactor = target;
    if (revealAnimator != null) { revealAnimator.cancel(); revealAnimator = null; }
    if (!animated) { applyRevealFactor(target); return; }
    revealAnimator = ValueAnimator.ofFloat(revealFactor, target);
    revealAnimator.setDuration(180);
    revealAnimator.setInterpolator(AnimatorUtils.DECELERATE_INTERPOLATOR);
    revealAnimator.addUpdateListener(animator -> applyRevealFactor((float) animator.getAnimatedValue()));
    revealAnimator.start();
  }

  private void applyRevealFactor (float factor) {
    revealFactor = factor;
    setVisibility(factor > 0f ? View.VISIBLE : View.GONE);
    setAlpha(factor);
    setTranslationY(Screen.dp(HEIGHT_DP) * (1f - factor));
    updateOccupiedHeight();
  }

  private void updateOccupiedHeight () {
    setEnabled(revealFactor == 1f && keyboardInset == 0);
    // The dock stays behind the IME. Reserve only the part the keyboard has uncovered.
    revealChanged.accept(Math.max(0f, revealFactor - keyboardInset / (float) Screen.dp(HEIGHT_DP)));
  }

  public void refresh () {
    tabs.removeAllViews();
    setBackgroundColor(Color.TRANSPARENT);
    hasApps = false;
    if (tdlib != null) {
      for (WebAppSession session : tdlib.webApps().getOpenSessions()) {
        if (!tdlib.webApps().isMinimized(session)) continue;
        hasApps = true;
        addTab(session);
      }
    }
    visibilityChanged.run();
  }

  private void addTab (WebAppSession session) {
    String name = TD.getUserName(session.tdlib.cache().user(session.request.botUserId));
    LinearLayout tab = new LinearLayout(activity);
    tab.setOrientation(LinearLayout.HORIZONTAL);
    tab.setLayoutDirection(Lang.rtl() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
    tab.setGravity(Gravity.CENTER_VERTICAL);
    ViewUtils.setBackground(tab, Theme.rectSelector(16f, 0f, ColorId.filling));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(tabWidth(), ViewGroup.LayoutParams.MATCH_PARENT);
    if (tabs.getChildCount() > 0) params.leftMargin = Screen.dp(8);
    tabs.addView(tab, params);

    View.OnClickListener restore = view -> {
      ViewController<?> owner = activity.navigation().getCurrentStackItem();
      if (revealFactor == 1f && keyboardInset == 0 && owner != null && owner.tdlib() == session.tdlib &&
          !activity.isActivityBusyWithSomething() && !activity.isPasscodeShowing() && !session.isClosed()) {
        session.tdlib.webApps().restore(owner, session);
      }
    };
    tab.setOnClickListener(restore);

    ImageView close = icon(R.drawable.baseline_close_24, Lang.getString(R.string.WebAppTabClose, name));
    close.setOnClickListener(view -> {
      if (revealFactor == 1f && keyboardInset == 0) session.close();
    });
    tab.addView(close, new LinearLayout.LayoutParams(Screen.dp(48), ViewGroup.LayoutParams.MATCH_PARENT));

    TextView title = new TextView(activity);
    title.setText(name);
    title.setTextColor(Theme.textAccentColor());
    title.setTextSize(16f);
    title.setTypeface(Fonts.getRobotoMedium());
    title.setGravity(Gravity.CENTER_VERTICAL | Lang.gravity());
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setPadding(Screen.dp(6), 0, Screen.dp(6), 0);
    title.setContentDescription(Lang.getString(R.string.WebAppTabRestore, name));
    title.setOnClickListener(restore);
    tab.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

    ImageView expand = icon(R.drawable.baseline_keyboard_arrow_up_24, Lang.getString(R.string.WebAppTabRestore, name));
    expand.setOnClickListener(restore);
    tab.addView(expand, new LinearLayout.LayoutParams(Screen.dp(48), ViewGroup.LayoutParams.MATCH_PARENT));
  }

  private ImageView icon (int resource, String description) {
    ImageView view = new ImageView(activity);
    view.setImageResource(resource);
    view.setColorFilter(Theme.textAccentColor());
    view.setScaleType(ImageView.ScaleType.CENTER);
    view.setContentDescription(description);
    RippleSupport.setTransparentSelector(view, 24f, null);
    return view;
  }

  private int tabWidth () {
    int width = getWidth() > 0 ? getWidth() : Screen.currentWidth();
    return Math.max(1, width - getPaddingLeft() - getPaddingRight());
  }

  @Override
  protected void onSizeChanged (int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    for (int i = 0; i < tabs.getChildCount(); i++) {
      View tab = tabs.getChildAt(i);
      tab.getLayoutParams().width = tabWidth();
      tab.requestLayout();
    }
  }

  public void destroy () {
    if (revealAnimator != null) { revealAnimator.cancel(); revealAnimator = null; }
    if (tdlib != null) tdlib.webApps().removeListener(appsChanged);
    tdlib = null;
    tabs.removeAllViews();
    hasApps = false;
  }
}
