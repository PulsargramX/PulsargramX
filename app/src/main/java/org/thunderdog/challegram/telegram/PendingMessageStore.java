/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Account-local bot drafts. These messages never enter TDLib's message or notification cache. */
public final class PendingMessageStore {
  public static final class PendingMessage {
    public final long chatId;
    public final int forumTopicId;
    public final long draftId;
    public final boolean canStop;
    public final boolean keepOnStop;
    public final boolean stopped;
    public final TdApi.MessageContent content;
    public final long localMessageId;
    public final int date;
    public final long updatedAt;
    public final long expiresAt;

    private PendingMessage (TdApi.UpdatePendingMessage update, long localMessageId, int date,
                            long updatedAt, long expiresAt, boolean stopped) {
      this.chatId = update.chatId;
      this.forumTopicId = update.forumTopicId;
      this.draftId = update.draftId;
      this.canStop = update.canStop;
      this.keepOnStop = update.keepOnStop;
      this.stopped = stopped;
      this.content = update.content;
      this.localMessageId = localMessageId;
      this.date = date;
      this.updatedAt = updatedAt;
      this.expiresAt = expiresAt;
    }

    public TdApi.MessageTopic topicId () {
      return forumTopicId != 0 ? new TdApi.MessageTopicForum(forumTopicId) : null;
    }

    private PendingMessage withState (boolean canStop, long expiresAt, boolean stopped) {
      return new PendingMessage(new TdApi.UpdatePendingMessage(chatId, forumTopicId, draftId,
        canStop, keepOnStop, content), localMessageId, date, updatedAt, expiresAt, stopped);
    }
  }

  /** A distinct type lets all message views reject server actions during construction as well. */
  public static final class Message extends TdApi.Message {
    public PendingMessage pendingMessage;

    public Message (PendingMessage pendingMessage, TdApi.MessageSender sender) {
      this.pendingMessage = pendingMessage;
      id = pendingMessage.localMessageId;
      chatId = pendingMessage.chatId;
      senderId = sender;
      date = pendingMessage.date;
      topicId = pendingMessage.topicId();
      content = pendingMessage.content;
      canBeSaved = true;
      authorSignature = "";
      senderTag = "";
      summaryLanguageCode = "";
      unreadReactions = new TdApi.UnreadReaction[0];
    }
  }

  private final Map<String, PendingMessage> messages = new HashMap<>();
  private final Map<String, Long> stoppedDrafts = new HashMap<>();
  private long nextLocalMessageId = Long.MIN_VALUE;
  private long periodMillis = 30_000;

  private static String key (long chatId, int forumTopicId) {
    return chatId + "_" + forumTopicId;
  }

  public synchronized PendingMessage get (long chatId, int forumTopicId, long now) {
    PendingMessage message = messages.get(key(chatId, forumTopicId));
    return message != null && now < message.expiresAt ? message : null;
  }

  public synchronized PendingMessage update (TdApi.UpdatePendingMessage update, long now, int date) {
    String key = key(update.chatId, update.forumTopicId);
    Long stoppedDraft = stoppedDrafts.get(key);
    if (stoppedDraft != null && stoppedDraft == update.draftId) {
      return null;
    }
    stoppedDrafts.remove(key);
    PendingMessage previous = messages.get(key);
    boolean sameDraft = previous != null && previous.draftId == update.draftId;
    PendingMessage message = new PendingMessage(update,
      sameDraft ? previous.localMessageId : ++nextLocalMessageId,
      sameDraft ? previous.date : date, now, now + periodMillis, false);
    messages.put(key, message);
    return message;
  }

  public synchronized boolean expire (long chatId, int forumTopicId, long draftId,
                                      long localMessageId, long expiresAt, long now) {
    String key = key(chatId, forumTopicId);
    PendingMessage current = messages.get(key);
    if (current == null || current.draftId != draftId || current.localMessageId != localMessageId ||
        current.expiresAt != expiresAt || now < current.expiresAt) {
      return false;
    }
    messages.remove(key);
    return true;
  }

  public synchronized boolean stop (long chatId, int forumTopicId, long draftId) {
    String key = key(chatId, forumTopicId);
    PendingMessage message = messages.get(key);
    if (message != null && message.draftId != draftId) {
      return false;
    }
    stoppedDrafts.put(key, draftId);
    if (message == null) {
      return false;
    }
    if (message.keepOnStop) {
      messages.put(key, message.withState(false, Long.MAX_VALUE, true));
    } else {
      messages.remove(key);
    }
    return true;
  }

  public synchronized boolean remove (long chatId, int forumTopicId) {
    return messages.remove(key(chatId, forumTopicId)) != null;
  }

  public synchronized List<PendingMessage> setPeriod (long seconds) {
    periodMillis = Math.max(0, Math.min(seconds, Integer.MAX_VALUE)) * 1000;
    List<PendingMessage> updated = new ArrayList<>(messages.size());
    for (Map.Entry<String, PendingMessage> entry : messages.entrySet()) {
      PendingMessage message = entry.getValue();
      message = message.withState(message.canStop,
        message.stopped ? Long.MAX_VALUE : message.updatedAt + periodMillis, message.stopped);
      entry.setValue(message);
      updated.add(message);
    }
    return updated;
  }

  public synchronized void clear () {
    messages.clear();
    stoppedDrafts.clear();
    periodMillis = 30_000;
  }
}
