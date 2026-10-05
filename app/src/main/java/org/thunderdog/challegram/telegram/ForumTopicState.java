package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import tgx.td.Td;

/** Snapshot operations shared by the topic cache and its readers. */
final class ForumTopicState {
  private ForumTopicState () { }

  static TdApi.ForumTopic copy (TdApi.ForumTopic topic) {
    // Rows replace message content, so the Message wrapper also belongs to the
    // snapshot. Other nested values are replaced as a whole by topic listeners.
    return new TdApi.ForumTopic(topic.info, Td.copyOf(topic.lastMessage), topic.order, topic.isPinned,
      topic.unreadCount, topic.lastReadInboxMessageId, topic.lastReadOutboxMessageId,
      topic.unreadMentionCount, topic.unreadReactionCount, topic.unreadPollVoteCount,
      topic.notificationSettings, topic.draftMessage);
  }

  static List<TdApi.ForumTopic> copy (List<TdApi.ForumTopic> topics) {
    List<TdApi.ForumTopic> result = new ArrayList<>(topics.size());
    for (TdApi.ForumTopic topic : topics) {
      result.add(copy(topic));
    }
    return result;
  }

  static List<TdApi.ForumTopic> merge (List<TdApi.ForumTopic> previous,
                                      List<TdApi.ForumTopic> incoming) {
    LinkedHashMap<Integer, TdApi.ForumTopic> byId = new LinkedHashMap<>();
    if (previous != null) {
      for (TdApi.ForumTopic topic : previous) {
        byId.put(topic.info.forumTopicId, topic);
      }
    }
    for (TdApi.ForumTopic topic : incoming) {
      byId.put(topic.info.forumTopicId, copy(topic));
    }
    List<TdApi.ForumTopic> result = new ArrayList<>(byId.values());
    ForumTopicOrder.applyPinnedOrder(result, incoming);
    return result;
  }

  static int countUnread (List<TdApi.ForumTopic> topics) {
    int count = 0;
    for (TdApi.ForumTopic topic : topics) {
      if (topic.unreadCount > 0) {
        count++;
      }
    }
    return count;
  }

  static boolean sameUpdate (TdApi.UpdateForumTopic a, TdApi.UpdateForumTopic b) {
    return a != null && b != null && a.isPinned == b.isPinned &&
      a.lastReadInboxMessageId == b.lastReadInboxMessageId &&
      a.lastReadOutboxMessageId == b.lastReadOutboxMessageId &&
      a.unreadMentionCount == b.unreadMentionCount &&
      a.unreadReactionCount == b.unreadReactionCount &&
      a.unreadPollVoteCount == b.unreadPollVoteCount &&
      Td.equalsTo(a.draftMessage, b.draftMessage);
  }

  static boolean matchesUpdate (TdApi.ForumTopic topic, TdApi.UpdateForumTopic update) {
    return update == null || (topic.isPinned == update.isPinned &&
      topic.lastReadInboxMessageId == update.lastReadInboxMessageId &&
      topic.lastReadOutboxMessageId == update.lastReadOutboxMessageId &&
      topic.unreadMentionCount == update.unreadMentionCount &&
      topic.unreadReactionCount == update.unreadReactionCount &&
      topic.unreadPollVoteCount == update.unreadPollVoteCount &&
      Td.equalsTo(topic.draftMessage, update.draftMessage));
  }

  static int unreadCounter (int unreadTopics, boolean hasUnreadMessages, boolean isMarkedAsUnread) {
    if (unreadTopics > 0) {
      return unreadTopics;
    }
    // An unknown topic count is not a message count. Keep an unread indicator
    // until a complete scan supplies the number, or restore the saved total.
    return isMarkedAsUnread || (unreadTopics < 0 && hasUnreadMessages) ?
      Tdlib.CHAT_MARKED_AS_UNREAD : 0;
  }
}
