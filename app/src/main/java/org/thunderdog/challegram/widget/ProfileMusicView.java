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
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.player.TGPlayerController;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.PlaybackController;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.core.ColorUtils;

/** The small artist/title pill drawn inside the expanding profile header. */
public final class ProfileMusicView {
  private final ViewController<?> controller;
  private final RectF bounds = new RectF();
  private final Path arrow = new Path();
  private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Drawable icon;
  private long userId;
  private TdApi.Audio audio;
  private boolean loading;

  public ProfileMusicView (ViewController<?> controller) {
    this.controller = controller;
    icon = Drawables.get(controller.context().getResources(), R.drawable.baseline_music_note_24);
    arrowPaint.setStyle(Paint.Style.STROKE);
    arrowPaint.setStrokeCap(Paint.Cap.ROUND);
    arrowPaint.setStrokeJoin(Paint.Join.ROUND);
  }

  public void setAudio (long userId, @Nullable TdApi.Audio audio) {
    this.userId = userId;
    this.audio = audio;
    bounds.setEmpty();
  }

  public boolean hasAudio () {
    return audio != null;
  }

  public CharSequence getContentDescription () {
    return audio != null ? Lang.getString(R.string.ProfileMusicAccessibility,
      TD.getTitle(audio), TD.getSubtitle(audio)) : "";
  }

  public void draw (Canvas canvas, int width, float centerY, float alpha,
                    int textColor, int backgroundColor) {
    bounds.setEmpty();
    if (audio == null || alpha <= 0f) return;
    TextPaint authorPaint = Paints.getMediumTextPaint(11f,
      ColorUtils.alphaColor(alpha, textColor), false);
    TextPaint titlePaint = Paints.getRegularTextPaint(11f,
      ColorUtils.alphaColor(.85f * alpha, textColor));
    float maxTextWidth = Math.max(0, width - Screen.dp(64f));
    CharSequence author = TextUtils.ellipsize(audio.performer != null ? audio.performer : "",
      authorPaint, maxTextWidth / 2f, TextUtils.TruncateAt.END);
    String song = TD.getTitle(audio);
    CharSequence title = TextUtils.ellipsize((author.length() > 0 ? " – " : "") + song,
      titlePaint, Math.max(0, maxTextWidth - authorPaint.measureText(author.toString())),
      TextUtils.TruncateAt.END);
    float authorWidth = authorPaint.measureText(author.toString());
    float titleWidth = titlePaint.measureText(title.toString());
    float pillWidth = Math.min(width - Screen.dp(24f),
      authorWidth + titleWidth + Screen.dp(42f));
    bounds.set((width - pillWidth) / 2f, centerY - Screen.dp(11f),
      (width + pillWidth) / 2f, centerY + Screen.dp(11f));
    canvas.drawRoundRect(bounds, Screen.dp(11f), Screen.dp(11f),
      Paints.fillingPaint(ColorUtils.alphaColor(alpha, backgroundColor)));
    float startX = bounds.left + Screen.dp(8f);
    boolean rtl = Lang.rtl();
    float iconX = rtl ? bounds.right - Screen.dp(21f) : startX;
    int save = canvas.save();
    canvas.translate(iconX, centerY - Screen.dp(6.5f));
    canvas.scale(13f / 24f, 13f / 24f);
    Drawables.draw(canvas, icon, 0, 0,
      Paints.getPorterDuffPaint(ColorUtils.alphaColor(alpha, textColor)));
    canvas.restoreToCount(save);
    float textX = rtl ? bounds.left + Screen.dp(21f) : startX + Screen.dp(17f);
    float baseline = centerY - (titlePaint.ascent() + titlePaint.descent()) / 2f;
    canvas.drawText(author.toString(), textX, baseline, authorPaint);
    canvas.drawText(title.toString(), textX + authorWidth, baseline, titlePaint);
    float arrowX = rtl ? bounds.left + Screen.dp(9f) : bounds.right - Screen.dp(10f);
    float direction = rtl ? -1 : 1;
    arrow.rewind();
    arrow.moveTo(arrowX, centerY - Screen.dp(3f));
    arrow.lineTo(arrowX + direction * Screen.dp(3f), centerY);
    arrow.lineTo(arrowX, centerY + Screen.dp(3f));
    arrowPaint.setColor(ColorUtils.alphaColor(.85f * alpha, textColor));
    arrowPaint.setStrokeWidth(Screen.dp(1.2f));
    canvas.drawPath(arrow, arrowPaint);
  }

  public boolean contains (float x, float y) {
    return !bounds.isEmpty() && x >= bounds.left && x <= bounds.right &&
      y >= bounds.top - Screen.dp(8f) && y <= bounds.bottom + Screen.dp(8f);
  }

  private void showPlayer () {
    if (controller.isDestroyed()) return;
    PlaybackController player = new PlaybackController(controller.context(), controller.tdlib());
    if (player.prepare() != -1) controller.navigateTo(player);
  }

  public void openPlaylist () {
    if (audio == null || loading || controller.isDestroyed()) return;
    TGPlayerController player = controller.tdlib().context().player();
    TdApi.Message current = player.getCurrentTrack();
    if (player.getProfileMusicUserId(controller.tdlib()) == userId &&
        current != null && player.getPlayState(controller.tdlib(), current) != TGPlayerController.STATE_NONE) {
      showPlayer();
      return;
    }
    loading = true;
    final long requestedUser = userId;
    final int requestedFile = audio.audio.id;
    controller.tdlib().send(new TdApi.GetUserProfileAudios(userId, 0, 100), (audios, error) ->
      controller.tdlib().uiExecute(() -> {
        loading = false;
        if (controller.isDestroyed() || userId != requestedUser || audio == null ||
            audio.audio.id != requestedFile) return;
        if (error != null) {
          UI.showError(error);
          return;
        }
        // Playback may have started while the profile request was in flight.
        if (player.getProfileMusicUserId(controller.tdlib()) == requestedUser) {
          showPlayer();
          return;
        }
        ArrayList<TdApi.Message> messages = new ArrayList<>(audios.audios.length);
        int origin = 0;
        for (TdApi.Audio item : audios.audios) {
          if (item.audio.id == requestedFile) origin = messages.size();
          messages.add(controller.tdlib().profileMusic().message(requestedUser, item));
        }
        if (messages.isEmpty()) return;
        final int index = origin;
        player.playPauseMessage(controller.tdlib(), messages.get(index),
          new TGPlayerController.PlayListBuilder() {
            @Override
            public TGPlayerController.PlayList buildPlayList (TdApi.Message fromMessage) {
              return new TGPlayerController.PlayList(messages, index)
                .setProfileMusicSource(requestedUser, audios.audios.length,
                  audios.audios.length >= audios.totalCount);
            }
            @Override
            public boolean wouldReusePlayList (TdApi.Message fromMessage, boolean reverse,
                boolean altered, List<TdApi.Message> tracks, long chatId) {
              return player.isProfileMusicSource(controller.tdlib(), requestedUser, null);
            }
          });
        showPlayer();
      }));
  }
}
