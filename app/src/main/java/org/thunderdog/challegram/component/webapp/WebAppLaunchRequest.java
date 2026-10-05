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

import android.net.Uri;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;

/** The public launch context. Authenticated URLs belong to the session, never to this request. */
public final class WebAppLaunchRequest {
  public enum Source {
    KEYBOARD, INLINE_QUERY, INLINE_BUTTON, BOT_MENU, ATTACHMENT_MENU, SIDE_MENU, LINK, MAIN,
    GUARD, AGE_VERIFICATION
  }

  public final Source source;
  public long botUserId;
  public long chatId;
  public long queryId;
  public @Nullable TdApi.MessageTopic topicId;
  public @Nullable TdApi.InputMessageReplyTo replyTo;
  public @Nullable TdApi.TargetChat targetChat;
  public String url = "";
  public String buttonText = "";
  public String botUsername = "";
  public String shortName = "";
  public String startParameter = "";
  public String originalUrl = "";
  public TdApi.WebAppOpenMode mode = new TdApi.WebAppOpenModeFullSize();
  public boolean hiddenLink;
  public @Nullable Runnable onFinished;
  public @Nullable java.util.function.Consumer<Boolean> onAgeVerified;

  public WebAppLaunchRequest (Source source, long botUserId) {
    this.source = source;
    this.botUserId = botUserId;
  }

  public WebAppLaunchRequest (WebAppLaunchRequest other) {
    this(other.source, other.botUserId);
    chatId = other.chatId;
    queryId = other.queryId;
    topicId = other.topicId;
    replyTo = other.replyTo;
    targetChat = other.targetChat;
    url = other.url;
    buttonText = other.buttonText;
    botUsername = other.botUsername;
    shortName = other.shortName;
    startParameter = other.startParameter;
    originalUrl = other.originalUrl;
    mode = other.mode;
    hiddenLink = other.hiddenLink;
    onAgeVerified = other.onAgeVerified;
  }

  public boolean hasChatSession () {
    return source == Source.INLINE_BUTTON || source == Source.BOT_MENU ||
      source == Source.ATTACHMENT_MENU;
  }

  /** A reusable public link, without the session's signed authentication data. */
  public @Nullable String publicLaunchUrl (Tdlib tdlib) {
    if (source == Source.GUARD || source == Source.AGE_VERIFICATION) return null;
    if (url != null && WebAppOrigin.origin(url) != null) return url;
    String username = botUsername;
    if (username == null || username.isEmpty()) {
      TdApi.User user = tdlib.cache().user(botUserId);
      if (user != null) username = tgx.td.Td.primaryUsername(user.usernames);
    }
    if (username == null || !username.matches("[A-Za-z0-9_]{1,64}")) return null;
    Uri.Builder link = new Uri.Builder().scheme("https").authority("t.me").appendPath(username);
    if (shortName != null && !shortName.isEmpty()) link.appendPath(shortName);
    boolean attachment = source == Source.ATTACHMENT_MENU || source == Source.SIDE_MENU;
    String parameter = attachment && url != null && url.startsWith("start://") ?
      url.substring("start://".length()) : startParameter;
    link.appendQueryParameter(attachment ? "startattach" : "startapp", parameter == null ? "" : parameter);
    return link.build().toString();
  }
}
