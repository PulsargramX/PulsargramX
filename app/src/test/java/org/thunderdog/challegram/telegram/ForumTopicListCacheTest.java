package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ForumTopicListCacheTest {
  @Test
  public void restoresStartupRowsWithoutInventingMessages () {
    TdApi.ForumTopic topic = topic(42, Long.MAX_VALUE - 100);
    topic.info.name = "Тема 🦊";
    topic.info.icon = new TdApi.ForumTopicIcon(0x6fb9f0, 987654321123456L);
    topic.info.isClosed = true;
    topic.info.isHidden = true;
    topic.isPinned = true;
    topic.unreadCount = 23;
    topic.lastReadInboxMessageId = 123456789012345L;
    topic.unreadReactionCount = 4;
    topic.notificationSettings = new TdApi.ChatNotificationSettings();
    topic.notificationSettings.muteFor = 120;

    List<TdApi.ForumTopic> restored = ForumTopicListCache.decode(-100,
      ForumTopicListCache.encode(Arrays.asList(topic)));
    assertNotNull(restored);
    TdApi.ForumTopic row = restored.get(0);
    assertEquals(-100, row.info.chatId);
    assertEquals(42, row.info.forumTopicId);
    assertEquals(topic.info.name, row.info.name);
    assertEquals(topic.info.icon.customEmojiId, row.info.icon.customEmojiId);
    assertEquals(topic.order, row.order);
    assertTrue(row.info.isClosed);
    assertTrue(row.info.isHidden);
    assertTrue(row.isPinned);
    assertEquals(23, row.unreadCount);
    assertEquals(topic.lastReadInboxMessageId, row.lastReadInboxMessageId);
    assertEquals(4, row.unreadReactionCount);
    assertEquals(120, row.notificationSettings.muteFor);
    assertNull(row.lastMessage);
    assertNull(row.draftMessage);
  }

  @Test
  public void keepsOnlyFirstHundredRowsInAuthoritativeOrder () {
    List<TdApi.ForumTopic> topics = new ArrayList<>();
    for (int i = 1; i <= 137; i++) topics.add(topic(i, i));
    List<TdApi.ForumTopic> restored = ForumTopicListCache.decode(-100, ForumTopicListCache.encode(topics));
    assertEquals(100, restored.size());
    assertEquals(137, restored.get(0).info.forumTopicId);
    assertEquals(38, restored.get(99).info.forumTopicId);
    assertEquals(1, topics.get(0).info.forumTopicId);
  }

  @Test
  public void rejectsTruncatedOrUnknownCacheAndPreservesEmptySnapshot () {
    byte[] bytes = ForumTopicListCache.encode(Arrays.asList(topic(1, 1)));
    assertNull(ForumTopicListCache.decode(-100, null));
    assertNull(ForumTopicListCache.decode(-100, Arrays.copyOf(bytes, bytes.length - 1)));
    bytes[3] = 99;
    assertNull(ForumTopicListCache.decode(-100, bytes));
    assertTrue(ForumTopicListCache.decode(-100,
      ForumTopicListCache.encode(new ArrayList<>())).isEmpty());
  }

  private static TdApi.ForumTopic topic (int id, long order) {
    TdApi.ForumTopic topic = new TdApi.ForumTopic();
    topic.info = new TdApi.ForumTopicInfo();
    topic.info.forumTopicId = id;
    topic.info.name = "Topic " + id;
    topic.order = order;
    return topic;
  }
}
