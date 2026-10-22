/*
 * This file is a part of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.data;

import org.drinkless.tdlib.TdApi;

import java.util.Locale;

/** Server-provided information about a user who started a conversation. */
public final class ChatAccountInfo {
  private ChatAccountInfo () { }

  public static TdApi.AccountInfo fromChat (TdApi.Chat chat, TdApi.User user) {
    if (chat == null || user == null || user.isSupport ||
        !(user.type instanceof TdApi.UserTypeRegular ||
          user.type instanceof TdApi.UserTypeDeleted) ||
        !(chat.type instanceof TdApi.ChatTypePrivate ||
          chat.type instanceof TdApi.ChatTypeSecret) ||
        !(chat.actionBar instanceof TdApi.ChatActionBarReportAddBlock)) {
      return null;
    }
    TdApi.AccountInfo info = ((TdApi.ChatActionBarReportAddBlock) chat.actionBar).accountInfo;
    return info != null && (hasRegistrationYear(info) || !phoneCountry(info).isEmpty()) ?
      info : null;
  }

  public static boolean hasRegistrationYear (TdApi.AccountInfo info) {
    return info.registrationYear > 0 && info.registrationYear <= 9999;
  }

  public static boolean hasRegistrationMonth (TdApi.AccountInfo info) {
    return hasRegistrationYear(info) && info.registrationMonth >= 1 && info.registrationMonth <= 12;
  }

  public static String phoneCountry (TdApi.AccountInfo info) {
    String code = info.phoneNumberCountryCode;
    if (code == null || code.length() != 2) {
      return "";
    }
    code = code.toUpperCase(Locale.US);
    return code.length() == 2 && code.charAt(0) >= 'A' && code.charAt(0) <= 'Z' &&
      code.charAt(1) >= 'A' && code.charAt(1) <= 'Z' ? code : "";
  }

  public static boolean showUnverifiedNotice (TdApi.User user) {
    return !user.isSupport && (user.verificationStatus == null ||
      (!user.verificationStatus.isVerified &&
        user.verificationStatus.botVerificationIconCustomEmojiId == 0));
  }
}
