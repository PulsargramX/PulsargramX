package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatTopicSupportTest {
  @Test
  public void detectsOnlyPrivateBotsAdvertisingTopics () {
    TdApi.UserTypeBot topicBot = new TdApi.UserTypeBot();
    topicBot.hasTopics = true;

    assertTrue(ChatTopicSupport.isPrivateChatWithTopics(
      new TdApi.ChatTypePrivate(), topicBot
    ));
    assertFalse(ChatTopicSupport.isPrivateChatWithTopics(
      new TdApi.ChatTypeSecret(), topicBot
    ));
    assertFalse(ChatTopicSupport.isPrivateChatWithTopics(
      new TdApi.ChatTypeSupergroup(), topicBot
    ));
  }

  @Test
  public void rejectsBotsWithoutTopicsAndNonBots () {
    TdApi.UserTypeBot regularBot = new TdApi.UserTypeBot();
    regularBot.hasTopics = false;

    assertFalse(ChatTopicSupport.isPrivateChatWithTopics(
      new TdApi.ChatTypePrivate(), regularBot
    ));
    assertFalse(ChatTopicSupport.isPrivateChatWithTopics(
      new TdApi.ChatTypePrivate(), new TdApi.UserTypeRegular()
    ));
    assertFalse(ChatTopicSupport.isPrivateChatWithTopics(null, regularBot));
    assertFalse(ChatTopicSupport.isPrivateChatWithTopics(
      new TdApi.ChatTypePrivate(), null
    ));
  }
}
