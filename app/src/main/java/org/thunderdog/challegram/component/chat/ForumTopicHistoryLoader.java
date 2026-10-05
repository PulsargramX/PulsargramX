package org.thunderdog.challegram.component.chat;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Recovers an initial topic history slice without confusing it with an empty topic. */
final class ForumTopicHistoryLoader {
  interface RequestSender {
    void send (TdApi.Function<?> function, Client.ResultHandler handler);
  }

  private final RequestSender sender;
  private final TdApi.GetForumTopicHistory request;
  private final boolean initial;
  private final LongSupplier lastMessageId;
  private final BooleanSupplier isActive;
  private final Client.ResultHandler handler;

  ForumTopicHistoryLoader (RequestSender sender, TdApi.GetForumTopicHistory request,
                           boolean initial, LongSupplier lastMessageId,
                           BooleanSupplier isActive, Client.ResultHandler handler) {
    this.sender = sender;
    this.request = request;
    this.initial = initial;
    this.lastMessageId = lastMessageId;
    this.isActive = isActive;
    this.handler = handler;
  }

  void load () {
    if (isActive.getAsBoolean()) {
      sender.send(request, result -> {
        if (!isActive.getAsBoolean()) {
          return;
        }
        boolean empty = result instanceof TdApi.Messages &&
          ((TdApi.Messages) result).messages.length == 0;
        boolean stale = request.fromMessageId == 0 && result instanceof TdApi.Messages &&
          !empty && ((TdApi.Messages) result).messages[0].id < lastMessageId.getAsLong();
        boolean transientError = result instanceof TdApi.Error &&
          ((TdApi.Error) result).code >= 500;
        if ((initial && (empty || stale)) || transientError) {
          recover(empty);
        } else {
          handler.onResult(result);
        }
      });
    }
  }

  private void recover (boolean empty) {
    // Mainline tracks top_message per topic. Refresh that boundary before
    // treating an initial history slice as empty or complete.
    sender.send(new TdApi.GetForumTopic(request.chatId, request.forumTopicId), topicResult -> {
      if (!isActive.getAsBoolean()) {
        return;
      }
      TdApi.Message latest = topicResult instanceof TdApi.ForumTopic ?
        ((TdApi.ForumTopic) topicResult).lastMessage : null;
      boolean loadLatest = initial && (empty || request.fromMessageId == 0);
      long fromMessageId = request.fromMessageId;
      int offset = request.offset;
      if (loadLatest) {
        fromMessageId = latest != null ? latest.id : 0;
        offset = latest != null ? -Math.min(19, request.limit - 1) : 0;
      }
      TdApi.GetForumTopicHistory retry = new TdApi.GetForumTopicHistory(request.chatId,
        request.forumTopicId, fromMessageId, offset, request.limit);
      sender.send(retry, result -> {
        if (!isActive.getAsBoolean()) {
          return;
        }
        if (loadLatest && latest != null && result instanceof TdApi.Messages) {
          TdApi.Messages messages = (TdApi.Messages) result;
          if (messages.messages.length == 0 || messages.messages[0].id < latest.id) {
            // The refreshed preview is a valid cached history seed. Discard the
            // disconnected slice and let normal backward pagination fill it in.
            int totalCount = messages.totalCount < 0 ? -1 : Math.max(1, messages.totalCount);
            result = new TdApi.Messages(totalCount,
              new TdApi.Message[] {latest});
          }
        }
        handler.onResult(result);
      });
    });
  }
}
