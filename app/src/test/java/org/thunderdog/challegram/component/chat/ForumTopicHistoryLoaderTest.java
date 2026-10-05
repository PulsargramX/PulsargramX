package org.thunderdog.challegram.component.chat;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ForumTopicHistoryLoaderTest {
  private static final long CHAT_ID = -100123;
  private static final int TOPIC_ID = 42;

  private static TdApi.Message message (long id) {
    TdApi.Message message = new TdApi.Message();
    message.id = id << 20;
    message.chatId = CHAT_ID;
    message.topicId = new TdApi.MessageTopicForum(TOPIC_ID);
    return message;
  }

  private static TdApi.Messages messages (long... ids) {
    TdApi.Message[] messages = new TdApi.Message[ids.length];
    for (int i = 0; i < ids.length; i++) {
      messages[i] = message(ids[i]);
    }
    return new TdApi.Messages(ids.length, messages);
  }

  private static TdApi.ForumTopic topic (long lastMessageId) {
    TdApi.ForumTopic topic = new TdApi.ForumTopic();
    topic.info = new TdApi.ForumTopicInfo();
    topic.info.chatId = CHAT_ID;
    topic.info.forumTopicId = TOPIC_ID;
    topic.lastMessage = lastMessageId != 0 ? message(lastMessageId) : null;
    return topic;
  }

  private static class Fixture implements ForumTopicHistoryLoader.RequestSender {
    final List<TdApi.Function<?>> requests = new ArrayList<>();
    final ArrayDeque<Client.ResultHandler> callbacks = new ArrayDeque<>();
    final List<TdApi.Object> results = new ArrayList<>();
    boolean active = true;
    long lastMessageId = 100 << 20;

    @Override
    public void send (TdApi.Function<?> function, Client.ResultHandler handler) {
      requests.add(function);
      callbacks.add(handler);
    }

    void load (long fromMessageId, int offset, boolean initial) {
      new ForumTopicHistoryLoader(this, new TdApi.GetForumTopicHistory(CHAT_ID, TOPIC_ID,
        fromMessageId << 20, offset, 33), initial, () -> lastMessageId, () -> active,
        results::add).load();
    }

    void respond (TdApi.Object response) {
      callbacks.remove().onResult(response);
    }

    TdApi.GetForumTopicHistory retry () {
      assertEquals(3, requests.size());
      assertTrue(requests.get(1) instanceof TdApi.GetForumTopic);
      TdApi.GetForumTopic metadata = (TdApi.GetForumTopic) requests.get(1);
      assertEquals(CHAT_ID, metadata.chatId);
      assertEquals(TOPIC_ID, metadata.forumTopicId);
      TdApi.GetForumTopicHistory retry = (TdApi.GetForumTopicHistory) requests.get(2);
      assertEquals(CHAT_ID, retry.chatId);
      assertEquals(TOPIC_ID, retry.forumTopicId);
      return retry;
    }
  }

  @Test
  public void currentBottomLoadsWithoutExtraRequests () {
    Fixture fixture = new Fixture();
    fixture.load(0, 0, true);
    TdApi.Messages response = messages(100, 99);
    fixture.respond(response);
    assertSame(response, fixture.results.get(0));
    assertEquals(1, fixture.requests.size());
  }

  @Test
  public void staleBottomRetriesAroundRefreshedLatestMessage () {
    Fixture fixture = new Fixture();
    fixture.load(0, 0, true);
    fixture.respond(messages(90, 89));
    assertTrue(fixture.results.isEmpty());
    fixture.respond(topic(101));
    assertEquals(101 << 20, fixture.retry().fromMessageId);
    assertEquals(-19, fixture.retry().offset);
    TdApi.Messages response = messages(102, 101, 100);
    fixture.respond(response);
    assertSame(response, fixture.results.get(0));
    assertEquals(3, fixture.requests.size());
  }

  @Test
  public void newPreviewWhileLoadingInvalidatesAnOlderBottom () {
    Fixture fixture = new Fixture();
    fixture.load(0, 0, true);
    fixture.lastMessageId = 101 << 20;
    fixture.respond(messages(100));
    assertEquals(2, fixture.requests.size());
    assertTrue(fixture.results.isEmpty());
  }

  @Test
  public void emptyUnreadAnchorFallsBackToTheExistingTopicHistory () {
    Fixture fixture = new Fixture();
    fixture.load(50, -19, true);
    fixture.respond(messages());
    fixture.respond(topic(100));
    assertEquals(100 << 20, fixture.retry().fromMessageId);
    TdApi.Messages response = messages(100, 99);
    fixture.respond(response);
    assertSame(response, fixture.results.get(0));
  }

  @Test
  public void emptyInitialHistoryIsVerifiedBeforeShowingAnEmptyTopic () {
    Fixture fixture = new Fixture();
    fixture.lastMessageId = 0;
    fixture.load(0, 0, true);
    fixture.respond(messages());
    assertTrue(fixture.results.isEmpty());
    fixture.respond(topic(0));
    assertEquals(0, fixture.retry().fromMessageId);
    assertEquals(0, fixture.retry().offset);
    TdApi.Messages empty = messages();
    fixture.respond(empty);
    assertSame(empty, fixture.results.get(0));
    assertEquals(3, fixture.requests.size());
  }

  @Test
  public void refreshedPreviewSeedsAnEmptyRetry () {
    Fixture fixture = new Fixture();
    fixture.load(0, 0, true);
    fixture.respond(messages());
    TdApi.ForumTopic topic = topic(100);
    fixture.respond(topic);
    fixture.respond(messages());
    TdApi.Messages response = (TdApi.Messages) fixture.results.get(0);
    assertEquals(1, response.messages.length);
    assertSame(topic.lastMessage, response.messages[0]);
    assertEquals(1, response.totalCount);
    assertEquals(3, fixture.requests.size());
  }

  @Test
  public void staleRetryUsesAConnectedSeedInsteadOfAppendingAcrossAGap () {
    Fixture fixture = new Fixture();
    fixture.load(0, 0, true);
    fixture.respond(messages(90, 89));
    fixture.respond(topic(100));
    fixture.respond(messages(95, 94));
    TdApi.Messages response = (TdApi.Messages) fixture.results.get(0);
    assertEquals(1, response.messages.length);
    assertEquals(100 << 20, response.messages[0].id);
  }

  @Test
  public void cachedSeedPreservesAnUnknownHistoryCount () {
    Fixture fixture = new Fixture();
    fixture.load(0, 0, true);
    fixture.respond(new TdApi.Messages(-1, new TdApi.Message[0]));
    fixture.respond(topic(100));
    fixture.respond(new TdApi.Messages(-1, new TdApi.Message[0]));
    TdApi.Messages response = (TdApi.Messages) fixture.results.get(0);
    assertEquals(-1, response.totalCount);
    assertEquals(100 << 20, response.messages[0].id);
  }

  @Test
  public void metadataFailureDoesNotResurrectAnUnverifiedPreview () {
    Fixture fixture = new Fixture();
    fixture.load(0, 0, true);
    fixture.respond(messages());
    fixture.respond(new TdApi.Error(500, "Temporary failure"));
    assertEquals(0, fixture.retry().fromMessageId);
    TdApi.Messages empty = messages();
    fixture.respond(empty);
    assertSame(empty, fixture.results.get(0));
  }

  @Test
  public void openingAnOlderMessageDoesNotRequireTheLatestMessageInItsSlice () {
    Fixture fixture = new Fixture();
    fixture.load(50, -19, true);
    TdApi.Messages response = messages(60, 50, 40);
    fixture.respond(response);
    assertSame(response, fixture.results.get(0));
    assertEquals(1, fixture.requests.size());
  }

  @Test
  public void emptyPaginationPageEndsNormallyWithoutRecovery () {
    Fixture fixture = new Fixture();
    fixture.load(100, -30, false);
    TdApi.Messages empty = messages();
    fixture.respond(empty);
    assertSame(empty, fixture.results.get(0));
    assertEquals(1, fixture.requests.size());
  }

  @Test
  public void transientPaginationErrorRetriesTheSamePageOnce () {
    Fixture fixture = new Fixture();
    fixture.load(70, -30, false);
    fixture.respond(new TdApi.Error(500, "Temporary failure"));
    fixture.respond(topic(100));
    assertEquals(70 << 20, fixture.retry().fromMessageId);
    assertEquals(-30, fixture.retry().offset);
    TdApi.Error error = new TdApi.Error(500, "Still unavailable");
    fixture.respond(error);
    assertSame(error, fixture.results.get(0));
    assertEquals(3, fixture.requests.size());
  }

  @Test
  public void transientInitialErrorKeepsTheRequestedHighlight () {
    Fixture fixture = new Fixture();
    fixture.load(50, -19, true);
    fixture.respond(new TdApi.Error(500, "Temporary failure"));
    fixture.respond(topic(100));
    assertEquals(50 << 20, fixture.retry().fromMessageId);
    assertEquals(-19, fixture.retry().offset);
  }

  @Test
  public void permanentErrorsAndFloodWaitsArePreserved () {
    for (int code : new int[] {400, 403, 404, 429}) {
      Fixture fixture = new Fixture();
      fixture.load(0, 0, true);
      TdApi.Error error = new TdApi.Error(code, "Unavailable");
      fixture.respond(error);
      assertSame(error, fixture.results.get(0));
      assertEquals(1, fixture.requests.size());
    }
  }

  @Test
  public void closingOrSwitchingTopicsCancelsEveryRecoveryStage () {
    for (int stage = 0; stage < 3; stage++) {
      Fixture fixture = new Fixture();
      fixture.load(0, 0, true);
      if (stage > 0) {
        fixture.respond(messages());
      }
      if (stage > 1) {
        fixture.respond(topic(100));
      }
      fixture.active = false;
      fixture.respond(stage == 1 ? topic(100) : messages(100));
      assertTrue(fixture.results.isEmpty());
      assertTrue(fixture.callbacks.isEmpty());
      assertEquals(stage + 1, fixture.requests.size());
    }
  }

  @Test
  public void inactiveLoaderDoesNotSendARequest () {
    Fixture fixture = new Fixture();
    fixture.active = false;
    fixture.load(0, 0, true);
    assertTrue(fixture.requests.isEmpty());
  }

  @Test
  public void recoveryOffsetRespectsASingleMessageLimit () {
    Fixture fixture = new Fixture();
    new ForumTopicHistoryLoader(fixture, new TdApi.GetForumTopicHistory(CHAT_ID, TOPIC_ID,
      0, 0, 1), true, () -> fixture.lastMessageId, () -> fixture.active,
      fixture.results::add).load();
    fixture.respond(messages());
    fixture.respond(topic(100));
    assertEquals(0, fixture.retry().offset);
    assertEquals(1, fixture.retry().limit);
  }
}
