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
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;

/** A transient removal notice with a single undo action, above the navigation inset. */
public final class ProfileMusicUndoView {
  private ProfileMusicUndoView () { }

  public static void show (ViewController<?> controller, Runnable restore) {
    if (controller.isDestroyed()) return;
    View target = controller.getValue();
    FrameLayout container = target instanceof FrameLayout ? (FrameLayout) target : null;
    // The activity container survives when removing the last track closes the player.
    while (target.getParent() instanceof View) {
      target = (View) target.getParent();
      if (target instanceof FrameLayout) container = (FrameLayout) target;
    }
    if (container == null) return;
    final FrameLayout root = container;
    View previous = root.findViewById(R.id.btn_profileMusicUndo);
    if (previous != null && previous.getParent() == root) root.removeView(previous);

    LinearLayout bar = new LinearLayout(controller.context());
    bar.setId(R.id.btn_profileMusicUndo);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setPadding(Screen.dp(16f), 0, Screen.dp(8f), 0);
    RippleSupport.setSimpleWhiteBackground(bar, ColorId.filling, Screen.dp(8f), controller);
    bar.setElevation(Screen.dp(4f));
    TextView text = new TextView(controller.context());
    text.setText(Lang.getString(R.string.AudioRemovedFromProfile));
    text.setTextSize(14f);
    text.setTextColor(Theme.getColor(ColorId.text));
    controller.addThemeTextColorListener(text, ColorId.text);
    bar.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    TextView undo = new TextView(controller.context());
    undo.setText(Lang.getString(R.string.ProfileMusicUndo));
    undo.setTextSize(14f);
    undo.setGravity(Gravity.CENTER);
    undo.setTextColor(Theme.getColor(ColorId.textLink));
    controller.addThemeTextColorListener(undo, ColorId.textLink);
    undo.setOnClickListener(v -> {
      root.removeView(bar);
      restore.run();
    });
    bar.addView(undo, new LinearLayout.LayoutParams(Screen.dp(64f), Screen.dp(52f)));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
    params.leftMargin = params.rightMargin = params.bottomMargin = Screen.dp(12f);
    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
    if (insets != null) params.bottomMargin += insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
    root.addView(bar, params);
    bar.postDelayed(() -> root.removeView(bar), 6000);
  }
}
