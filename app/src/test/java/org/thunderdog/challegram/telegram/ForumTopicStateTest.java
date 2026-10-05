package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class ForumTopicStateTest {
  @Test
  public void copiedSnapshotsDoNotShareMutableTopicState () {
    TdApi.ForumTopic original = topic(1, "original", 10, false);
    original.order = 100;
    original.lastReadInboxMessageId = 50;
    original.lastMessage = new TdApi.Message();
    TdApi.MessageContent originalContent = new TdApi.MessageText();
    original.lastMessage.content = originalContent;
    List<TdApi.ForumTopic> source = new ArrayList<>();
    source.add(original);

    List<TdApi.ForumTopic> snapshot = ForumTopicState.copy(source);
    TdApi.ForumTopic copied = snapshot.get(0);
    copied.unreadCount = 0;
    copied.order = 200;
    copied.lastReadInboxMessageId = 75;
    copied.lastMessage.content = new TdApi.MessagePhoto();
    snapshot.clear();

    assertNotSame(original, copied);
    assertEquals(1, source.size());
    assertEquals(10, original.unreadCount);
    assertEquals(100, original.order);
    assertEquals(50, original.lastReadInboxMessageId);
    assertNotSame(original.lastMessage, copied.lastMessage);
    assertEquals(originalContent, original.lastMessage.content);
  }

  @Test
  public void mergedPagesDeduplicateByIdAndKeepExistingTail () {
    List<TdApi.ForumTopic> firstPage = Arrays.asList(
      topic(1, "old one", 1, false),
      topic(2, "old two", 2, false),
      topic(3, "cached tail", 3, false)
    );
    List<TdApi.ForumTopic> nextPage = Arrays.asList(
      topic(1, "new one", 10, false),
      topic(2, "new two", 20, false),
      topic(4, "four", 4, false)
    );

    List<TdApi.ForumTopic> merged = ForumTopicState.merge(firstPage, nextPage);

    assertEquals(4, merged.size());
    assertEquals(1, merged.get(0).info.forumTopicId);
    assertEquals("new one", merged.get(0).info.name);
    assertEquals(10, merged.get(0).unreadCount);
    assertEquals(2, merged.get(1).info.forumTopicId);
    assertEquals("new two", merged.get(1).info.name);
    assertEquals(20, merged.get(1).unreadCount);
    assertEquals(3, merged.get(2).info.forumTopicId);
    assertEquals("cached tail", merged.get(2).info.name);
    assertEquals(4, merged.get(3).info.forumTopicId);
  }

  @Test
  public void unreadCountIncludesHiddenTopicsButExcludesReadTopics () {
    List<TdApi.ForumTopic> topics = Arrays.asList(
      topic(1, "hidden unread", 5, true),
      topic(2, "visible read", 0, false),
      topic(3, "visible unread", 2, false)
    );

    assertEquals(2, ForumTopicState.countUnread(topics));
  }

  @Test
  public void repeatedReadResponseUpdatesDoNotInvalidateAnotherFetch () {
    TdApi.UpdateForumTopic first = new TdApi.UpdateForumTopic();
    first.lastReadInboxMessageId = 25;
    TdApi.UpdateForumTopic echo = new TdApi.UpdateForumTopic();
    echo.lastReadInboxMessageId = 25;
    org.junit.Assert.assertTrue(ForumTopicState.sameUpdate(first, echo));
    TdApi.ForumTopic response = topic(1, "topic", 0, false);
    response.lastReadInboxMessageId = 25;
    org.junit.Assert.assertTrue(ForumTopicState.matchesUpdate(response, echo));

    echo.lastReadInboxMessageId = 26;
    org.junit.Assert.assertFalse(ForumTopicState.sameUpdate(first, echo));
    org.junit.Assert.assertFalse(ForumTopicState.matchesUpdate(response, echo));
  }

  @Test
  public void draftChangesRemainDifferentFromResponseEchoes () {
    TdApi.UpdateForumTopic old = new TdApi.UpdateForumTopic();
    TdApi.UpdateForumTopic changed = new TdApi.UpdateForumTopic();
    changed.draftMessage = new TdApi.DraftMessage();
    org.junit.Assert.assertFalse(ForumTopicState.sameUpdate(old, changed));
    org.junit.Assert.assertFalse(ForumTopicState.matchesUpdate(topic(1, "topic", 0, false), changed));
    TdApi.ForumTopic response = topic(1, "topic", 0, false);
    response.draftMessage = changed.draftMessage;
    org.junit.Assert.assertTrue(ForumTopicState.matchesUpdate(response, changed));
  }

  @Test
  public void badgeCountsTopicsRegardlessOfMessageVolume () {
    List<TdApi.ForumTopic> topics = Arrays.asList(
      topic(1, "busy", 1200, false),
      topic(2, "one unread", 1, false),
      topic(3, "read", 0, false)
    );

    assertEquals(2, ForumTopicState.unreadCounter(ForumTopicState.countUnread(topics), true, false));
  }

  @Test
  public void unknownTotalUsesIndicatorUntilTopicCountIsAvailable () {
    assertEquals(Tdlib.CHAT_MARKED_AS_UNREAD, ForumTopicState.unreadCounter(-1, true, false));
    assertEquals(0, ForumTopicState.unreadCounter(-1, false, false));
    assertEquals(Tdlib.CHAT_MARKED_AS_UNREAD, ForumTopicState.unreadCounter(-1, false, true));
    assertEquals(3, ForumTopicState.unreadCounter(3, true, false));
    assertEquals(0, ForumTopicState.unreadCounter(0, true, false));
    assertEquals(Tdlib.CHAT_MARKED_AS_UNREAD, ForumTopicState.unreadCounter(0, false, true));
  }

  @Test
  public void unreadCountIsNotLimitedToOnePage () {
    List<TdApi.ForumTopic> topics = new ArrayList<>();
    for (int topicId = 1; topicId <= 137; topicId++) {
      topics.add(topic(topicId, "topic " + topicId, 1, false));
    }

    assertEquals(137, ForumTopicState.countUnread(topics));
  }

  private static TdApi.ForumTopic topic (int topicId, String name, int unreadCount,
                                         boolean isHidden) {
    TdApi.ForumTopicInfo info = new TdApi.ForumTopicInfo(
      100, topicId, name, new TdApi.ForumTopicIcon(0, 0), 0, null,
      topicId == 1, false, isHidden, isHidden, false
    );
    return new TdApi.ForumTopic(
      info, null, topicId, false, unreadCount, 0, 0, 0, 0, 0, null, null
    );
  }
}
