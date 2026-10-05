#!/usr/bin/env python3
"""Exercise topic history recovery and production loading decisions without Android."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

from message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]
CHAT = "app/src/main/java/org/thunderdog/challegram/component/chat/"


def junit_jar(coordinate: str) -> Path:
    matches = list((Path.home() / ".gradle/caches/modules-2/files-2.1" / coordinate).glob("*/*.jar"))
    if not matches:
        raise unittest.SkipTest(f"Requires cached {coordinate}")
    return matches[0]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class ForumTopicHistoryTest(unittest.TestCase):
    def test_history_recovery_and_topic_loading_state(self) -> None:
        loader = read(CHAT + "MessagesLoader.java")
        methods = "\n".join(java_method(loader, signature) for signature in (
            "public long getChatId ()",
            "public boolean isForumTopic ()",
            "public TdApi.ForumTopic getForumTopic ()",
            "public long getLastMessageId ()",
            "public long getLastReadInboxMessageId ()",
            "private TdApi.Function<TdApi.Messages> newHistoryRequest (",
            "private boolean isEndReached (MessageId messageId)",
        ))
        begin = loader.index("canLoadBottom = specialMode != SPECIAL_MODE_SCHEDULED &&")
        bottom_decision = loader[begin:loader.index(";", begin) + 1]
        begin = loader.index("case TdApi.Error.CONSTRUCTOR: {")
        error_decision = loader[loader.index("{", begin) + 1:
                                loader.index("messages = new TdApi.Message[0];", begin)]
        scroll = java_method(read(CHAT + "MessagesManager.java"),
                             "public void scrollToStart (boolean force)")
        harness = r"""
import org.drinkless.tdlib.TdApi;

public class TopicLoadingStateTest {
  static final int SPECIAL_MODE_RESTRICTED = 3, SPECIAL_MODE_SCHEDULED = 4;
  static final int SPECIAL_MODE_NONE = 0, MERGE_MODE_NONE = 0;
  TdApi.Chat chat = new TdApi.Chat();
  ThreadInfo messageThread;
  TdApi.MessageTopic topicId;
  Tdlib tdlib = new Tdlib();
  Manager manager = new Manager();
  int specialMode;
  TdApi.SearchMessagesFilter searchFilter;
  int mergeMode;
  long contextId = 1;
  final Object lock = new Object();
  Object lastHandler = new Object();
  boolean isLoading = true, convertedErrorToEmpty;
  METHODS
  boolean hasSearchFilter() {return searchFilter != null;}
  void handleError(TdApi.Object object, long currentContextId) {
    ERROR_DECISION
    convertedErrorToEmpty = true;
  }
  boolean hasNewer(MessageId scrollMessageId, TGMessage suitableMessage) {
    boolean canLoadBottom;
    boolean canLoadMore = true;
    BOTTOM_DECISION
    return canLoadBottom;
  }
  static class Tdlib {
    TdApi.ForumTopic topic;
    TdApi.ForumTopic forumTopic(long chatId, long topicId) {return topic;}
  }
  static class Manager {
    Controller controller = new Controller();
    int awaitFinished;
    Controller controller() {return controller;}
    long maxPinnedMessageId() {return 0;}
    void onNetworkRequestSent() {awaitFinished++;}
  }
  static class Controller {
    TdApi.ForumTopic topic;
    TdApi.ForumTopic getForumTopic() {return topic;}
  }
  static class ThreadInfo {
    long getChatId() {return 10;}
    long getLastMessageId() {return 500;}
    long getLastReadInboxMessageId() {return 400;}
    long getOldestMessageId() {return 42;}
  }
  static class MessageId {
    long chatId, id;
    MessageId(long chatId, long id) {this.chatId = chatId; this.id = id;}
    long getChatId() {return chatId;}
    long getMessageId() {return id;}
    boolean isHistoryEnd() {return id == 0;}
  }
  static class TGMessage {
    long id;
    TGMessage(long id) {this.id = id;}
    long getChatId() {return 10;}
    long getId() {return id;}
  }
  static class Log {
    static final int TAG_MESSAGES_LOADER = 1;
    static void ensureReturnType(Class<?> request, Class<?> response) {}
    static void w(int tag, String format, String value) {}
  }
  static class TD {static String toErrorString(TdApi.Object object) {return "Error";}}
  static class UI {
    static java.util.ArrayDeque<Runnable> callbacks = new java.util.ArrayDeque<>();
    static int errors;
    static void post(Runnable runnable) {callbacks.add(runnable);}
    static void showError(TdApi.Object object) {errors++;}
    static void flush() {while (!callbacks.isEmpty()) callbacks.remove().run();}
  }
  static class Td {
    static boolean isPinnedFilter(TdApi.SearchMessagesFilter filter) {
      return filter instanceof TdApi.SearchMessagesFilterPinned;
    }
  }
  static class ArrayUtils {
    static long[] removeElement(long[] array, int index) {
      return java.util.Arrays.copyOf(array, array.length - 1);
    }
  }
  static class BottomNavigation {
    static final int HIGHLIGHT_MODE_NORMAL = 0;
    TopicLoadingStateTest loader;
    MessageId highlightMessageId = new MessageId(10, 70);
    long[] returnToMessageIds = {70};
    boolean special, returns, wasScrollByUser = true;
    int reloads, scrolls, jumps;
    BottomNavigation(TopicLoadingStateTest loader) {this.loader = loader;}
    boolean inSpecialMode() {return special;}
    boolean hasReturnMessage() {return returns;}
    void stopScroll() {}
    void scrollToBottom(boolean smooth) {scrolls++;}
    void loadFromStart() {reloads++;}
    void revokeReturnMessages() {returns = false;}
    void highlightMessage(MessageId id, int mode, long[] ids, boolean force) {jumps++;}
    SCROLL
  }
  boolean canLoadBottom() {return false;}
  static TdApi.Message message(long id) {
    TdApi.Message message = new TdApi.Message(); message.id = id; return message;
  }
  static void check(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }
  public static void main(String[] args) {
    TopicLoadingStateTest loader = new TopicLoadingStateTest();
    loader.chat.id = 10;
    loader.chat.lastMessage = message(1000);
    loader.chat.lastReadInboxMessageId = 900;
    loader.topicId = new TdApi.MessageTopicForum(42);
    TdApi.ForumTopic topic = new TdApi.ForumTopic();
    topic.lastMessage = message(100);
    topic.lastReadInboxMessageId = 50;
    loader.tdlib.topic = topic;
    check(loader.getLastMessageId() == 100, "Latest message must belong to the topic");
    check(loader.getLastReadInboxMessageId() == 50, "Read position must belong to the topic");
    check(!loader.isEndReached(new MessageId(10, 80)), "Topic history still has newer messages");
    check(loader.isEndReached(new MessageId(10, 100)), "Topic end must ignore another topic's message");
    check(!loader.isEndReached(new MessageId(11, 100)), "Another chat cannot end this history");
    check(loader.hasNewer(new MessageId(10, 0), new TGMessage(80)),
      "A stale bottom response cannot close forward pagination");
    check(!loader.hasNewer(new MessageId(10, 0), new TGMessage(100)),
      "The current topic bottom closes forward pagination");
    loader.chat.lastMessage = message(10);
    check(!loader.isEndReached(new MessageId(10, 80)), "An older parent preview cannot end a topic");
    TdApi.GetForumTopicHistory history = (TdApi.GetForumTopicHistory)
      loader.newHistoryRequest(10, 80, -9, 10, true);
    check(history.forumTopicId == 42 && history.fromMessageId == 80 && history.offset == -9,
      "Album completion must keep the topic scope and offset");
    loader.topicId = new TdApi.MessageTopicForum(1);
    history = (TdApi.GetForumTopicHistory) loader.newHistoryRequest(10, 80, 0, 10, true);
    check(history.forumTopicId == 1, "General uses forum topic history too");
    loader.tdlib.topic = null;
    loader.manager.controller.topic = topic;
    check(loader.getLastMessageId() == 100, "A directly opened topic supplies its own metadata");
    loader.manager.controller.topic = null;
    check(loader.getLastMessageId() == 0 && loader.getLastReadInboxMessageId() == 0,
      "Unknown topic metadata cannot fall back to parent state");
    check(!loader.isEndReached(new MessageId(10, 100)), "An unknown topic end stays unknown");
    loader.handleError(new TdApi.Error(500, "Unavailable"), loader.contextId);
    check(!loader.convertedErrorToEmpty, "History failure cannot be converted to empty messages");
    UI.flush();
    check(!loader.isLoading && loader.lastHandler == null && UI.errors == 1 &&
      loader.manager.awaitFinished == 1, "Failure releases loading and reports the error");
    loader.isLoading = true;
    loader.handleError(new TdApi.Error(500, "Old request"), loader.contextId);
    loader.contextId++;
    loader.lastHandler = new Object();
    UI.flush();
    check(loader.isLoading && loader.lastHandler != null && UI.errors == 1,
      "A delayed failure cannot finish a newer history request");
    loader.mergeMode = 1;
    loader.handleError(new TdApi.Error(500, "Album completion"), loader.contextId);
    check(loader.convertedErrorToEmpty && UI.callbacks.isEmpty(),
      "Failed album completion still processes the already received history slice");
    loader.mergeMode = MERGE_MODE_NONE;
    BottomNavigation navigation = new BottomNavigation(loader);
    navigation.scrollToStart(false);
    check(navigation.reloads == 1 && navigation.scrolls == 0,
      "Go to bottom must fetch current topic history even after reaching the previous end");
    navigation.returns = true;
    navigation.scrollToStart(false);
    check(navigation.jumps == 1 && navigation.reloads == 1, "Return-to-message navigation is preserved");
    navigation.scrollToStart(true);
    check(navigation.reloads == 2 && !navigation.returns, "Forced bottom navigation refreshes history");
    loader.messageThread = new ThreadInfo();
    check(!loader.isForumTopic() && loader.getLastMessageId() == 500 &&
      loader.getLastReadInboxMessageId() == 400, "Reply threads keep their own state");
    check(loader.newHistoryRequest(10, 80, 0, 10, true) instanceof TdApi.GetMessageThreadHistory,
      "Reply thread history routing is preserved");
    loader.messageThread = null;
    loader.topicId = null;
    check(loader.getLastMessageId() == 10 && loader.getLastReadInboxMessageId() == 900,
      "Ordinary chat state is preserved");
    TdApi.GetChatHistory chatHistory = (TdApi.GetChatHistory)
      loader.newHistoryRequest(10, 80, 0, 10, true);
    check(chatHistory.onlyLocal, "Ordinary album cache requests remain local");
    navigation.returns = false;
    navigation.scrollToStart(false);
    check(navigation.scrolls == 1 && navigation.reloads == 2, "Ordinary bottom scroll is preserved");
    System.out.println("Topic loading, album scope, and bottom navigation checks passed");
  }
}
""".replace("METHODS", methods).replace("BOTTOM_DECISION", bottom_decision).replace(
            "ERROR_DECISION", error_decision).replace("SCROLL", scroll)
        jars = [junit_jar("junit/junit/4.13.2"),
                junit_jar("org.hamcrest/hamcrest-core/1.3")]
        with tempfile.TemporaryDirectory(prefix="forum-history-") as temp:
            work = Path(temp)
            annotations = []
            for name, body in (
                ("Nullable", "public @interface Nullable {}"),
                ("IntDef", "public @interface IntDef {long[] value() default {}; "
                           "boolean flag() default false;}"),
            ):
                target = work / "androidx/annotation" / f"{name}.java"
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("package androidx.annotation; " + body)
                annotations.append(target)
            state_test = work / "TopicLoadingStateTest.java"
            state_test.write_text(harness)
            classes = work / "classes"
            classes.mkdir()
            classpath = os.pathsep.join(str(jar) for jar in jars)
            sources = [
                *annotations,
                ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java",
                ROOT / "tdlib/src/main/java/org/drinkless/tdlib/Client.java",
                ROOT / CHAT / "ForumTopicHistoryLoader.java",
                ROOT / "app/src/test/java/org/thunderdog/challegram/component/chat/"
                       "ForumTopicHistoryLoaderTest.java",
                state_test,
            ]
            subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath,
                            "-d", str(classes), *map(str, sources)], check=True)
            classpath = str(classes) + os.pathsep + classpath
            subprocess.run(["java", "-cp", classpath, "org.junit.runner.JUnitCore",
                            "org.thunderdog.challegram.component.chat.ForumTopicHistoryLoaderTest"],
                           check=True)
            subprocess.run(["java", "-cp", classpath, "TopicLoadingStateTest"], check=True)


if __name__ == "__main__":
    unittest.main()
