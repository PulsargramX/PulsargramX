package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;

/** Maps external message_thread_id values to the TDLib topic type used by a chat. */
public final class MessageTopicRouting {
  private MessageTopicRouting () { }

  public static TdApi.MessageTopic fromExternalMessageThreadId (
    long messageThreadId,
    boolean hasTopics,
    boolean isRepliesChat,
    boolean hasMessageThreads
  ) {
    if (messageThreadId <= 0) {
      return null;
    }

    // Replies uses message-thread IDs even when its bot user advertises topics.
    if (isRepliesChat) {
      return new TdApi.MessageTopicThread(messageThreadId);
    }

    // TDLib represents both supergroup forum topics and private bot topics as MessageTopicForum.
    if (hasTopics) {
      if (messageThreadId > Integer.MAX_VALUE) {
        return null;
      }
      return new TdApi.MessageTopicForum((int) messageThreadId);
    }

    if (hasMessageThreads) {
      return new TdApi.MessageTopicThread(messageThreadId);
    }

    return null;
  }
}
