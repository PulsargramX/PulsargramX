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
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.PorterDuffColorId;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.PorterDuffPaint;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.DrawableProvider;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSets;
import org.thunderdog.challegram.util.text.TextEntity;
import org.thunderdog.challegram.util.text.TextStyleProvider;

import me.vkryl.android.util.ViewProvider;
import me.vkryl.core.StringUtils;
import me.vkryl.core.collection.IntList;
import tgx.td.Td;

/** Shared message preview presentation for chat and topic rows. */
public final class ChatListPreview {
  private ChatListPreview () { }

  public static TextStyleProvider getTextStyleProvider (int listMode) {
    return Paints.robotoStyleProvider(listMode == Settings.CHAT_MODE_3LINE ? 15 : 16);
  }

  public static @Nullable String getSenderName (Tdlib tdlib, TdApi.Message message, int listMode) {
    if (message.isOutgoing) {
      return Lang.getString(listMode != Settings.CHAT_MODE_2LINE &&
        tdlib.isMultiChat(message.chatId) && Td.getSenderId(message) == message.chatId ?
        R.string.FromYouAnonymous : R.string.FromYou);
    } else if (!Td.isProximityAlertTriggered(message.content)) {
      return listMode == Settings.CHAT_MODE_2LINE &&
        Td.getMessageAuthorId(message) == message.chatId &&
        StringUtils.isEmpty(message.authorSignature) ?
        Lang.getString(R.string.FromAnonymous) :
        tdlib.senderName(message, false, listMode == Settings.CHAT_MODE_2LINE);
    }
    return null;
  }

  public static IntList getIconIds (ContentPreview preview, @Nullable TdApi.Message message,
                                    boolean hasAuthor) {
    IntList icons = new IntList(2);
    if (!hasAuthor && message != null && Td.isCall(message.content)) {
      addIcon(icons, CallItem.getSubtitleIcon((TdApi.MessageCall) message.content, TD.isOut(message)));
      return icons;
    }
    if (preview.parentEmoji != null) {
      addIcon(icons, preview.parentEmoji.iconRepresentation);
    } else if (message != null) {
      boolean needShareIcon = false;
      if (message.forwardInfo != null) {
        TdApi.MessageOrigin origin = message.forwardInfo.origin;
        switch (origin.getConstructor()) {
          case TdApi.MessageOriginChannel.CONSTRUCTOR:
            needShareIcon = message.chatId != ((TdApi.MessageOriginChannel) origin).chatId;
            break;
          case TdApi.MessageOriginHiddenUser.CONSTRUCTOR:
            needShareIcon = true;
            break;
          case TdApi.MessageOriginUser.CONSTRUCTOR:
            needShareIcon = Td.getSenderUserId(message) !=
              ((TdApi.MessageOriginUser) origin).senderUserId;
            break;
          case TdApi.MessageOriginChat.CONSTRUCTOR:
            needShareIcon = Td.getSenderId(message) !=
              ((TdApi.MessageOriginChat) origin).senderChatId;
            break;
          default:
            Td.assertMessageOrigin_f2224a59();
            throw Td.unsupported(origin);
        }
      }
      if (needShareIcon) {
        addIcon(icons, R.drawable.baseline_share_arrow_16);
      } else if (message.importInfo != null) {
        addIcon(icons, R.drawable.templarian_baseline_import_16);
      } else if (message.replyTo instanceof TdApi.MessageReplyToMessage &&
                 ((TdApi.MessageReplyToMessage) message.replyTo).chatId != message.chatId) {
        addIcon(icons, R.drawable.baseline_reply_16);
      }
    }
    if (preview.emoji != null) {
      addIcon(icons, preview.emoji.iconRepresentation);
    }
    return icons;
  }

  private static void addIcon (IntList icons, int icon) {
    if (icon != 0) {
      icons.append(icon);
    }
  }

  public static @PorterDuffColorId int getIconColorId (@Nullable TdApi.Message message,
                                                      boolean hasAuthor) {
    return !hasAuthor && message != null && Td.isCall(message.content) ?
      CallItem.getSubtitleIconColorId((TdApi.MessageCall) message.content) : ColorId.chatListIcon;
  }

  public static @Nullable Text buildPrefix (@Nullable String prefix, int availableWidth,
                                            int listMode, boolean isDraft, boolean needSuffix) {
    if (availableWidth <= 0 || StringUtils.isEmpty(prefix)) {
      return null;
    }
    Text.Builder builder = new Text.Builder(prefix,
      listMode == Settings.CHAT_MODE_2LINE ? Math.min(availableWidth, Screen.dp(120f)) : availableWidth,
      getTextStyleProvider(listMode),
      isDraft ? TextColorSets.Regular.NEGATIVE : TextColorSets.Regular.NORMAL)
      .singleLine()
      .textFlags(Text.FLAG_ELLIPSIZE_NO_FILL);
    if (needSuffix && listMode == Settings.CHAT_MODE_2LINE) {
      builder.suffix(": ");
    }
    return builder.build();
  }

  public static @Nullable Text buildText (String text, @Nullable TextEntity[] entities,
                                          int availableWidth, int listMode, boolean hasPrefix,
                                          @Nullable IntList icons, ViewProvider viewProvider,
                                          Text.TextMediaListener mediaListener) {
    if (availableWidth <= 0 || StringUtils.isEmpty(text)) {
      return null;
    }
    boolean singleLine = listMode == Settings.CHAT_MODE_2LINE || hasPrefix;
    int iconsPadding = icons != null && icons.size() > 0 ?
      Screen.dp(2f) + Screen.dp(18f) * icons.size() : 0;
    return new Text.Builder(text, availableWidth, getTextStyleProvider(listMode),
      TextColorSets.Regular.LIGHT)
      .maxLineCount(singleLine ? 1 : 2)
      .textFlags(Text.FLAG_ELLIPSIZE_NEWLINE)
      .ignoreContinuousNewLines()
      .ignoreNewLines(singleLine)
      .lineMarginProvider((lineIndex, y, defaultMaxWidth, lineHeight) ->
        lineIndex == 0 ? iconsPadding : 0)
      .viewProvider(viewProvider)
      .entities(entities, mediaListener)
      .noClickable()
      .build();
  }

  public static int getSingleLineOffset () {
    return Screen.dp(2f);
  }

  public static void draw (Canvas canvas, int listMode, int left, int top, int viewWidth,
                           boolean rtl, float alpha, @Nullable Text prefix, @Nullable Text text,
                           @Nullable IntList icons, @PorterDuffColorId int iconColorId,
                           ComplexReceiver receiver, DrawableProvider drawableProvider) {
    if (prefix != null) {
      prefix.draw(canvas, left, top, null, alpha);
      if (listMode == Settings.CHAT_MODE_2LINE) {
        left += prefix.getWidth();
      } else {
        top += prefix.getNextLineHeight();
      }
    } else if (text != null && listMode != Settings.CHAT_MODE_2LINE && text.getLineCount() == 1) {
      top += getSingleLineOffset();
    }
    if (text == null) {
      return;
    }
    if (icons != null) {
      int x = left;
      for (int i = 0; i < icons.size(); i++) {
        int iconId = icons.get(i);
        Drawable drawable = drawableProvider.getSparseDrawable(iconId, ColorId.NONE);
        int y = top + text.getLineHeight(false) / 2 - drawable.getMinimumHeight() / 2;
        if (iconId == R.drawable.baseline_camera_alt_16) {
          y += Screen.dp(.5f);
        }
        Drawables.drawRtl(canvas, drawable, x, y, PorterDuffPaint.get(iconColorId, alpha),
          viewWidth, rtl);
        x += Screen.dp(18f);
      }
    }
    text.draw(canvas, left, top, null, alpha, receiver);
  }
}
