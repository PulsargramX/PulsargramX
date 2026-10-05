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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;

import me.vkryl.core.ColorUtils;

public class ChatBoostProgressView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path pointer = new Path();
  private final Drawable bolt;
  private TdApi.ChatBoostStatus status;
  private ValueAnimator animator;
  private float progress;

  public ChatBoostProgressView (Context context) {
    super(context);
    setLayoutParams(new RecyclerView.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    paint.setTypeface(Fonts.getRobotoMedium());
    bolt = context.getResources().getDrawable(R.drawable.baseline_flash_on_24).mutate();
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  private float badgeHeight () {
    return Math.max(Screen.dp(48f), Screen.sp(22f) + Screen.dp(18f));
  }

  private float barHeight () {
    return Math.max(Screen.dp(32f), Screen.sp(14f) + Screen.dp(16f));
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
      resolveSize((int) (badgeHeight() + barHeight() + Screen.dp(44f)), heightMeasureSpec));
  }

  public void setStatus (TdApi.ChatBoostStatus status) {
    boolean animate = this.status != null && isLaidOut();
    this.status = status;
    setContentDescription(status.nextLevelBoostCount == 0 ?
      Lang.getString(R.string.ChatBoostProgressMax, status.level,
        Strings.buildCounter(status.boostCount)) :
      Lang.getString(R.string.ChatBoostProgress, status.level,
        Strings.buildCounter(status.boostCount), Strings.buildCounter(status.nextLevelBoostCount)));
    int range = status.nextLevelBoostCount - status.currentLevelBoostCount;
    float target = status.nextLevelBoostCount == 0 ? 1f : range <= 0 ? 0f :
      Math.max(0f, Math.min(1f,
        (float) (status.boostCount - status.currentLevelBoostCount) / range));
    if (animator != null) {
      animator.cancel();
    }
    if (animate && target != progress) {
      animator = ValueAnimator.ofFloat(progress, target);
      animator.setDuration(320L);
      animator.setInterpolator(new DecelerateInterpolator());
      animator.addUpdateListener(animation -> {
        progress = (float) animation.getAnimatedValue();
        invalidate();
      });
      animator.start();
    } else {
      progress = target;
      invalidate();
    }
  }

  @Override
  protected void onDetachedFromWindow () {
    if (animator != null) {
      animator.end();
    }
    super.onDetachedFromWindow();
  }

  @Override
  protected void onDraw (Canvas canvas) {
    if (status == null)
      return;
    float left = Screen.dp(20f), right = getWidth() - left;
    if (right <= left)
      return;
    float badgeTop = Screen.dp(16f), badgeBottom = badgeTop + badgeHeight();
    float top = badgeBottom + Screen.dp(20f), bottom = top + barHeight();
    float radius = Screen.dp(8f);
    float edge = Lang.rtl() ? right - (right - left) * progress :
      left + (right - left) * progress;
    paint.setColor(ColorUtils.fromToArgb(Theme.fillingColor(),
      Theme.getColor(ColorId.textNeutral), .14f));
    canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
    canvas.save();
    pointer.reset();
    pointer.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW);
    canvas.clipPath(pointer);
    paint.setColor(Theme.getColor(ColorId.fillingPositive));
    canvas.drawRect(Lang.rtl() ? edge : left, top, Lang.rtl() ? right : edge, bottom, paint);
    String current = Lang.getString(R.string.ChatBoostLevel, status.level);
    String next = status.nextLevelBoostCount == 0 ? Lang.getString(R.string.ChatBoostMaxShort) :
      Lang.getString(R.string.ChatBoostLevel, status.level + 1);
    paint.setTextSize(Screen.sp(14f));
    float textWidth = paint.measureText(current) + paint.measureText(next);
    if (textWidth > right - left - Screen.dp(40f)) {
      paint.setTextSize(paint.getTextSize() * (right - left - Screen.dp(40f)) / textWidth);
    }
    float baseline = (top + bottom - paint.ascent() - paint.descent()) / 2f;
    String start = Lang.rtl() ? next : current, end = Lang.rtl() ? current : next;
    paint.setColor(Theme.getColor(ColorId.text));
    canvas.drawText(start, left + Screen.dp(12f), baseline, paint);
    canvas.drawText(end, right - Screen.dp(12f) - paint.measureText(end), baseline, paint);
    canvas.clipRect(Lang.rtl() ? edge : left, top, Lang.rtl() ? right : edge, bottom);
    paint.setColor(Theme.getColor(ColorId.fillingPositiveContent));
    canvas.drawText(start, left + Screen.dp(12f), baseline, paint);
    canvas.drawText(end, right - Screen.dp(12f) - paint.measureText(end), baseline, paint);
    canvas.restore();

    String count = Strings.buildCounter(status.boostCount);
    paint.setTextSize(Screen.sp(22f));
    if (paint.measureText(count) > right - left - Screen.dp(58f)) {
      paint.setTextSize(paint.getTextSize() *
        Math.max(Screen.dp(8f), right - left - Screen.dp(58f)) / paint.measureText(count));
    }
    float badgeWidth = Math.min(right - left, paint.measureText(count) + Screen.dp(58f));
    float center = Math.max(left + badgeWidth / 2f, Math.min(right - badgeWidth / 2f, edge));
    float badgeLeft = center - badgeWidth / 2f, badgeRight = center + badgeWidth / 2f;
    paint.setColor(Theme.getColor(ColorId.fillingPositive));
    canvas.drawRoundRect(badgeLeft, badgeTop, badgeRight, badgeBottom,
      badgeHeight() / 2f, badgeHeight() / 2f, paint);
    pointer.reset();
    pointer.moveTo(center - Screen.dp(7f), badgeBottom - Screen.dp(2f));
    pointer.lineTo(center, badgeBottom + Screen.dp(8f));
    pointer.lineTo(center + Screen.dp(7f), badgeBottom - Screen.dp(2f));
    pointer.close();
    canvas.drawPath(pointer, paint);
    bolt.setTint(Theme.getColor(ColorId.fillingPositiveContent));
    int boltTop = (int) ((badgeTop + badgeBottom) / 2f) - Screen.dp(13f);
    bolt.setBounds((int) badgeLeft + Screen.dp(14f), boltTop,
      (int) badgeLeft + Screen.dp(40f), boltTop + Screen.dp(26f));
    bolt.draw(canvas);
    paint.setColor(Theme.getColor(ColorId.fillingPositiveContent));
    canvas.drawText(count, badgeLeft + Screen.dp(42f),
      (badgeTop + badgeBottom - paint.ascent() - paint.descent()) / 2f, paint);
  }
}
