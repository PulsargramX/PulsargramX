package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;

/** Capability checks for chats whose messages are partitioned into forum topics. */
public final class ChatTopicSupport {
  private ChatTopicSupport () { }

  public static boolean isPrivateChatWithTopics (TdApi.ChatType chatType, TdApi.UserType userType) {
    return chatType != null &&
      chatType.getConstructor() == TdApi.ChatTypePrivate.CONSTRUCTOR &&
      userType != null &&
      userType.getConstructor() == TdApi.UserTypeBot.CONSTRUCTOR &&
      ((TdApi.UserTypeBot) userType).hasTopics;
  }
}
