#!/usr/bin/env python3
"""Run unread poll navigation against the real TDLib request classes without Android."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class PollNavigationTest(unittest.TestCase):
    def test_topic_retry_pending_request_and_stale_results(self):
        source = read("app/src/main/java/org/thunderdog/challegram/component/chat/MessagesManager.java")
        methods = "\n".join(java_method(source, signature) for signature in (
            "private void resetUnreadPollVotesNavigation ()",
            "private void setUnreadPollVotes (",
            "private void setUnreadPollVotesImpl (",
            "public void scrollToNextUnreadPollVote ()",
        ))
        harness = r'''
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.drinkless.tdlib.TdApi;
class PollNavigationHarness {
  ArrayList<TdApi.Message> closestUnreadPollVotes;
  CancellableResultHandler pollVotesHandler;
  long lastViewedPollVoteMessageId;
  Controller controller = new Controller();
  Loader loader = new Loader();
  Adapter adapter = new Adapter();
  Tdlib tdlib = new Tdlib();
  List<Long> highlighted = new ArrayList<>();
  static final int HIGHLIGHT_MODE_NORMAL = 1;
  boolean special;
  boolean inSpecialMode() {return special;}
  void highlightMessage(MessageId id, int mode, Object quote, boolean smooth) {
    highlighted.add(id.id);
  }
  METHODS
  static class Controller {boolean destroyed; boolean isDestroyed() {return destroyed;}}
  static class Loader {
    TdApi.MessageTopic topic = new TdApi.MessageTopicForum(42);
    long chatId = 7;
    long getChatId() {return chatId;}
    TdApi.MessageTopic getTopicId() {return topic;}
  }
  static class TGMessage {long getBiggestId() {return 100;}}
  static class Adapter {TGMessage getTopActiveMessage() {return new TGMessage();}}
  static class MessageId {long id; MessageId(long chat, long id) {this.id = id;}}
  abstract static class CancellableResultHandler {
    boolean cancelled;
    void cancel() {cancelled = true;}
    abstract public void processResult(TdApi.Object object);
  }
  static class Tdlib {
    List<TdApi.SearchChatMessages> requests = new ArrayList<>();
    List<Runnable> queued = new ArrayList<>();
    Tdlib client() {return this;}
    Tdlib ui() {return this;}
    void post(Runnable runnable) {queued.add(runnable);}
    void send(TdApi.SearchChatMessages request, CancellableResultHandler handler) {requests.add(request);}
    void drain() {for (Runnable r : new ArrayList<>(queued)) r.run(); queued.clear();}
  }
  static void check(boolean condition) {if (!condition) throw new AssertionError();}
  static TdApi.FoundChatMessages result(int total, long... ids) {
    TdApi.FoundChatMessages result = new TdApi.FoundChatMessages();
    result.totalCount = total;
    result.messages = new TdApi.Message[ids.length];
    for (int i = 0; i < ids.length; i++) {
      result.messages[i] = new TdApi.Message();
      result.messages[i].id = ids[i]; result.messages[i].chatId = 7;
    }
    return result;
  }
  public static void main(String[] args) {
    PollNavigationHarness h = new PollNavigationHarness();
    h.scrollToNextUnreadPollVote(); h.scrollToNextUnreadPollVote();
    check(h.tdlib.requests.size() == 1);
    TdApi.SearchChatMessages first = h.tdlib.requests.get(0);
    check(first.chatId == 7 && first.topicId == h.loader.topic);
    check(first.filter instanceof TdApi.SearchMessagesFilterUnreadPollVote);
    check(first.fromMessageId == 100);
    CancellableResultHandler old = h.pollVotesHandler;
    old.processResult(result(2));
    check(h.tdlib.requests.size() == 2);
    TdApi.SearchChatMessages retry = h.tdlib.requests.get(1);
    check(retry.fromMessageId == 0 && retry.topicId == h.loader.topic);
    old.processResult(result(2)); h.tdlib.drain();
    check(h.tdlib.requests.size() == 2 && h.pollVotesHandler == null);
    check(h.highlighted.isEmpty());
    h.scrollToNextUnreadPollVote();
    old.processResult(result(1, 5)); h.tdlib.drain();
    check(h.highlighted.isEmpty());
    h.pollVotesHandler.processResult(result(2, 60, 50)); h.tdlib.drain();
    check(h.highlighted.equals(Arrays.asList(60L)));
    h.scrollToNextUnreadPollVote();
    check(h.highlighted.equals(Arrays.asList(60L, 50L)));
    h.controller.destroyed = true; h.scrollToNextUnreadPollVote();
    check(h.tdlib.requests.size() == 3);
    h.controller.destroyed = false; h.special = true; h.scrollToNextUnreadPollVote();
    check(h.tdlib.requests.size() == 3);
    h.special = false;
    h.scrollToNextUnreadPollVote();
    CancellableResultHandler pending = h.pollVotesHandler;
    pending.processResult(result(2, 30, 20));
    h.lastViewedPollVoteMessageId = 90;
    h.closestUnreadPollVotes = new ArrayList<>(Arrays.asList(result(1, 10).messages));
    h.resetUnreadPollVotesNavigation();
    check(pending.cancelled && h.pollVotesHandler == null);
    check(h.closestUnreadPollVotes == null && h.lastViewedPollVoteMessageId == 0);
    h.loader.chatId = 8;
    h.loader.topic = new TdApi.MessageTopicForum(99);
    h.scrollToNextUnreadPollVote();
    TdApi.SearchChatMessages afterReuse = h.tdlib.requests.get(4);
    check(afterReuse.chatId == 8 && afterReuse.topicId == h.loader.topic);
    check(afterReuse.fromMessageId == 100);
    h.tdlib.drain();
    check(h.highlighted.equals(Arrays.asList(60L, 50L)));
    check(h.pollVotesHandler != null && h.pollVotesHandler != pending);
  }
}
'''.replace("METHODS", methods)
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            annotations = []
            for name, body in (("Nullable", "public @interface Nullable {}"),
                               ("IntDef", "public @interface IntDef {long[] value() default {}; boolean flag() default false;}")):
                path = work / "androidx/annotation" / (name + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package androidx.annotation; " + body)
                annotations.append(path)
            test = work / "PollNavigationHarness.java"
            test.write_text(harness)
            sources = [*annotations, test,
                       ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java"]
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work),
                            *map(str, sources)], check=True)
            subprocess.run(["java", "-cp", str(work), "PollNavigationHarness"], check=True)


if __name__ == "__main__":
    unittest.main()
