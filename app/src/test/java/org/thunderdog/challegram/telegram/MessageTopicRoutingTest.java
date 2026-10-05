package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MessageTopicRoutingTest {
  @Test
  public void topicChatThreadIdUsesForumTopicRepresentation () {
    TdApi.MessageTopic topic = MessageTopicRouting.fromExternalMessageThreadId(
      42, true, false, false
    );

    assertTrue(topic instanceof TdApi.MessageTopicForum);
    assertEquals(42, ((TdApi.MessageTopicForum) topic).forumTopicId);
  }

  @Test
  public void repliesChatTakesPrecedenceOverTopicChat () {
    TdApi.MessageTopic topic = MessageTopicRouting.fromExternalMessageThreadId(
      7, true, true, true
    );

    assertTrue(topic instanceof TdApi.MessageTopicThread);
    assertEquals(7, ((TdApi.MessageTopicThread) topic).messageThreadId);
  }

  @Test
  public void repliesAndNonForumSupergroupsUseMessageThreads () {
    TdApi.MessageTopic repliesTopic = MessageTopicRouting.fromExternalMessageThreadId(
      1L << 40, false, true, false
    );
    TdApi.MessageTopic supergroupTopic = MessageTopicRouting.fromExternalMessageThreadId(
      123456789L, false, false, true
    );

    assertTrue(repliesTopic instanceof TdApi.MessageTopicThread);
    assertEquals(1L << 40, ((TdApi.MessageTopicThread) repliesTopic).messageThreadId);
    assertTrue(supergroupTopic instanceof TdApi.MessageTopicThread);
    assertEquals(123456789L, ((TdApi.MessageTopicThread) supergroupTopic).messageThreadId);
  }

  @Test
  public void regularBotsAndInvalidThreadIdsAreIgnored () {
    assertNull(MessageTopicRouting.fromExternalMessageThreadId(
      0, true, false, false
    ));
    assertNull(MessageTopicRouting.fromExternalMessageThreadId(
      (long) Integer.MAX_VALUE + 1, true, false, false
    ));
    assertNull(MessageTopicRouting.fromExternalMessageThreadId(
      9, false, false, false
    ));
  }
}
