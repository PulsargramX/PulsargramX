package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ForumTopicOrderTest {
  @Test
  public void activityRefreshKeepsPinsAheadInServerSequence () {
    List<TdApi.ForumTopic> topics = topics();
    ForumTopicOrder.sort(topics);
    assertIds(topics, 1, 2, 4, 3);
    topics.get(0).order = 2000;
    topics.get(1).order = 3000;
    ForumTopicOrder.sort(topics);
    assertIds(topics, 1, 2, 4, 3);
  }

  @Test
  public void pageRefreshAdoptsNewPinSequenceButSingleRefreshPreservesIt () {
    List<TdApi.ForumTopic> topics = topics();
    ForumTopicOrder.applyPinnedOrder(topics, Arrays.asList(topics.get(1), topics.get(0)));
    ForumTopicOrder.sort(topics);
    assertIds(topics, 2, 1, 4, 3);
    ForumTopicOrder.applyPinnedOrder(topics, Collections.singletonList(topics.get(1)));
    ForumTopicOrder.sort(topics);
    assertIds(topics, 2, 1, 4, 3);
  }

  @Test
  public void startupCachePreservesPinsAndActivityOrder () {
    assertIds(ForumTopicListCache.decode(-100, ForumTopicListCache.encode(topics())), 1, 2, 4, 3);
  }

  @Test
  public void incomingActivityReordersAnUnopenedForumWithoutMovingPins () {
    List<TdApi.ForumTopic> topics = topics();
    TdApi.ForumTopic active = topics.get(2);
    active.lastMessage = message(12, 100);
    ForumTopicOrder.update(active);
    ForumTopicOrder.sort(topics);
    assertIds(topics, 1, 2, 3, 4);
    assertEquals((100L << 32) + 12, active.order);
  }

  @Test
  public void draftRemovalRestoresMessageOrderInsteadOfKeepingTheMaximum () {
    TdApi.ForumTopic topic = topics().get(0);
    topic.lastMessage = message(25, 100);
    topic.draftMessage = new TdApi.DraftMessage();
    topic.draftMessage.date = 200;
    ForumTopicOrder.update(topic);
    assertEquals(200L << 32, topic.order);
    topic.draftMessage = null;
    ForumTopicOrder.update(topic);
    assertEquals((100L << 32) + 25, topic.order);
  }

  @Test
  public void sameSecondMessagesUseServerIdAndBeatDrafts () {
    TdApi.ForumTopic first = topics().get(2);
    TdApi.ForumTopic second = topics().get(3);
    first.lastMessage = message(41, 100);
    second.lastMessage = message(40, 100);
    first.draftMessage = new TdApi.DraftMessage();
    first.draftMessage.date = 100;
    ForumTopicOrder.update(first);
    ForumTopicOrder.update(second);
    List<TdApi.ForumTopic> topics = new ArrayList<>(Arrays.asList(second, first));
    ForumTopicOrder.sort(topics);
    assertIds(topics, 3, 4);
    assertEquals((100L << 32) + 41, first.order);
  }

  @Test
  public void localIdBitsAndScheduledMessagesDoNotDistortOrder () {
    TdApi.ForumTopic topic = topics().get(0);
    topic.lastMessage = message(42, 100);
    topic.lastMessage.id += (1 << 20) - 1;
    ForumTopicOrder.update(topic);
    assertEquals((100L << 32) + 42, topic.order);
    topic.lastMessage.schedulingState = new TdApi.MessageSchedulingStateSendAtDate();
    ForumTopicOrder.update(topic);
    assertEquals(0, topic.order);
  }

  @Test
  public void deletingLastMessageAndClearingAnEmptyTopicsDraftCanLowerOrder () {
    TdApi.ForumTopic topic = topics().get(0);
    topic.lastMessage = message(50, 500);
    ForumTopicOrder.update(topic);
    topic.lastMessage = message(10, 100);
    ForumTopicOrder.update(topic);
    assertEquals((100L << 32) + 10, topic.order);
    topic.lastMessage = null;
    topic.draftMessage = new TdApi.DraftMessage();
    topic.draftMessage.date = 200;
    ForumTopicOrder.update(topic);
    assertEquals(200L << 32, topic.order);
    topic.draftMessage = null;
    ForumTopicOrder.update(topic);
    assertEquals(0, topic.order);
  }

  private static TdApi.Message message (long serverId, int date) {
    TdApi.Message message = new TdApi.Message();
    message.id = serverId << 20;
    message.date = date;
    return message;
  }

  private static List<TdApi.ForumTopic> topics () {
    List<TdApi.ForumTopic> topics = new ArrayList<>();
    for (int id = 1; id <= 4; id++) {
      TdApi.ForumTopic topic = new TdApi.ForumTopic();
      topic.info = new TdApi.ForumTopicInfo();
      topic.info.forumTopicId = id;
      topic.info.name = "Topic " + id;
      topic.isPinned = id <= 2;
      topic.order = id * 100;
      topics.add(topic);
    }
    return topics;
  }

  private static void assertIds (List<TdApi.ForumTopic> topics, int... ids) {
    assertEquals(ids.length, topics.size());
    for (int i = 0; i < ids.length; i++) assertEquals(ids[i], topics.get(i).info.forumTopicId);
  }
}
