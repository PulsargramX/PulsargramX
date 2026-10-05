package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** TDLib's forum topic order describes activity, not pin priority or pin sequence. */
public final class ForumTopicOrder {
  private ForumTopicOrder () { }

  /** Mirrors ForumTopic::get_forum_topic_order and DialogDate in the bundled TDLib.
   * TDLib message IDs contain 20 low bits of local/type information. Scheduled
   * messages never participate in the topic's activity order.
   */
  public static void update (TdApi.ForumTopic topic) {
    long order = 0;
    if (topic.lastMessage != null && topic.lastMessage.schedulingState == null) {
      order = ((long) topic.lastMessage.date << 32) + (topic.lastMessage.id >> 20);
    }
    if (topic.draftMessage != null) {
      order = Math.max(order, (long) topic.draftMessage.date << 32);
    }
    topic.order = Math.max(0, order);
  }

  public static void sort (List<TdApi.ForumTopic> topics) {
    // List.sort is stable: pinned rows retain the sequence supplied by the server.
    topics.sort((a, b) -> {
      if (a.isPinned != b.isPinned) return a.isPinned ? -1 : 1;
      if (a.isPinned) return 0;
      int order = Long.compare(b.order, a.order);
      return order != 0 ? order : Integer.compare(b.info.forumTopicId, a.info.forumTopicId);
    });
  }

  public static void applyPinnedOrder (List<TdApi.ForumTopic> topics,
                                       List<TdApi.ForumTopic> incoming) {
    Map<Integer, Integer> positions = new HashMap<>();
    for (TdApi.ForumTopic topic : incoming) {
      if (topic.isPinned) positions.put(topic.info.forumTopicId, positions.size());
    }
    if (positions.size() < 2) return;
    List<TdApi.ForumTopic> pinned = new ArrayList<>();
    for (TdApi.ForumTopic topic : topics) {
      if (topic.isPinned && positions.containsKey(topic.info.forumTopicId)) pinned.add(topic);
    }
    pinned.sort((a, b) -> Integer.compare(positions.get(a.info.forumTopicId),
      positions.get(b.info.forumTopicId)));
    int index = 0;
    // Replace only participating slots so a partial page or single-topic refresh
    // cannot move pins that it doesn't contain.
    for (int i = 0; i < topics.size(); i++) {
      TdApi.ForumTopic topic = topics.get(i);
      if (topic.isPinned && positions.containsKey(topic.info.forumTopicId)) {
        topics.set(i, pinned.get(index++));
      }
    }
  }
}
