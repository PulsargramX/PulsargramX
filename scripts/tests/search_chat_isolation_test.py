"""Exercise search exit and screen reuse with production lifecycle code."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]
CHAT = "app/src/main/java/org/thunderdog/challegram/component/chat/"


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class SearchChatIsolationTest(unittest.TestCase):
  def test_search_exit_and_delayed_results_stay_in_their_chat(self):
    controller = read("app/src/main/java/org/thunderdog/challegram/ui/MessagesController.java")
    manager = read(CHAT + "MessagesManager.java")
    controller_methods = "\n".join(java_method(controller, signature) for signature in (
      "protected void onLeaveSearchMode ()",
      "private void resetSearchControls (boolean animated)",
      "private void onSetSearchFilteredShowMode (boolean inSearchMode)",
    ))
    manager_methods = "\n".join(java_method(manager, signature) for signature in (
      "public void destroy (ViewController<?> context)",
      "public void showSearchResult (",
      "public void onDestroySearch ()",
    ))
    middleware_counter = java_method(read(CHAT + "MessagesSearchManagerMiddleware.java"),
      "private int getTotalCount (")
    harness = r'''
import java.util.*;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.component.chat.MessagesSearchManager;
import org.thunderdog.challegram.component.chat.MessagesSearchManagerMiddleware;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import tgx.td.MessageId;

public class SearchChatIsolationTest {
  static class ViewController<T> {}
  static class Controller extends ViewController<Object> {
    TdApi.Chat chat = new TdApi.Chat();
    final Manager manager = new Manager(this);
    final Tdlib tdlib = manager.tdlib;
    MessageId searchFromUserMessageId;
    boolean searchMessagesFilterMode, areScheduled, inPreviewMode, searchControlsForChannel;
    int extraBottomInset, completedSearches;
    Object messageThread;
    TdApi.MessageTopic messageTopicId;
    TdApi.SearchMessagesFilter previewSearchFilter;
    String lastMessageSearchQuery, lastMembersSearchQuery;
    Layout searchControlsLayout;
    final Text searchCounterView = new Text();
    final Badge goToPrevFoundMessageButtonBadge = new Badge();
    final Badge goToNextFoundMessageButtonBadge = new Badge();
    final Animator searchNavigationButtonVisibleAnimator = new Animator();
    Button searchShowOnlyFoundButton;
    CONTROLLER_METHODS
    long getChatId() {return chat.id;}
    boolean isInForceTouchMode() {return false;}
    void searchMedia(String query) {}
    void onSetSearchTypeFilter(int index) {}
    void onSetSearchMessagesSenderId(TdApi.MessageSender sender) {}
    void showSearchByUserView(boolean visible, boolean animated) {}
    void setSearchInProgress(boolean loading, boolean animated) {}
    void checkSearchByVisible() {}
    void checkSearchFilteredModeButton(boolean animated) {}
    void setSearchControlsFactor(float factor) {}
    void updateSearchNavigation() {}
    String getLastMessageSearchQuery() {return "query";}
    void applyQueryForManagerInFilteredShowMode(String query) {}
    void onChatSearchStarted() {}
    void onChatSearchFinished(String counter, int index, int total) {completedSearches++;}
  }
  static class Loader {
    long chatId = 10;
    long getChatId() {return chatId;}
    void reuse() {}
  }
  static class Adapter {
    void clear(boolean destroyed) {}
    void checkAllMessages() {}
  }
  static class MessagesManager {
    static final int HIGHLIGHT_MODE_WITHOUT_HIGHLIGHT = -2;
  }
  static class Manager implements MessagesSearchManager.Delegate {
    static final int HIGHLIGHT_MODE_NORMAL = 1, HIGHLIGHT_MODE_WITHOUT_HIGHLIGHT = -2;
    final Controller controller;
    final Tdlib tdlib = new Tdlib();
    final Loader loader = new Loader();
    final Adapter adapter = new Adapter();
    final MessagesSearchManagerMiddleware searchMiddleware = new MessagesSearchManagerMiddleware();
    final MessagesSearchManager searchManager = new MessagesSearchManager(tdlib, this, searchMiddleware);
    final List<MessageId> navigations = new ArrayList<>();
    final Map<Long, Object> memberTags = new HashMap<>();
    MessageId highlightMessageId;
    TdApi.InputTextQuote highlightQuote;
    String highlightPollOptionId;
    Runnable pendingQuoteScroll;
    long[] returnToMessageIds;
    int highlightMode;
    long lastViewedMentionMessageId, lastViewedReactionMessageId, requestedChat;
    Object chatAdmins, mentionsHandler, reactionsHandler, closestMentions, closestUnreadReactions;
    Object messageViewer;
    Destroyable pinnedMessages;
    boolean hasScrolled, pinnedMessagesAvailable, hasVisibleProtectedContent;
    boolean awaitingForPinnedMessages, wasScrollByUser;
    Manager(Controller controller) {this.controller = controller;}
    MANAGER_METHODS
    void setHighlightMessageId(MessageId messageId, int mode) {highlightMessageId = messageId;}
    void highlightMessage(MessageId messageId, int mode, long[] returns, boolean smooth) {
      highlightMessageId = messageId;
      navigations.add(messageId);
    }
    void openChat(TdApi.Chat chat, Object thread, TdApi.MessageTopic topic,
                  TdApi.SearchMessagesFilter filter, Controller context,
                  boolean scheduled, boolean pinned) {
      loader.chatId = chat.id;
      requestedChat = highlightMessageId != null ? highlightMessageId.getChatId() : chat.id;
    }
    Adapter getAdapter() {return adapter;}
    boolean isEventLog() {return false;}
    Object eventLogFilters, eventLogUserIds;
    void applyEventLogFilters(Object filters, String query, Object users) {}
    void resetScroll() {}
    void unsubscribeFromUpdates() {}
    void resetUnreadPollVotesNavigation() {}
    void resetSponsoredContext() {}
    void clearHeaderMessage() {}
    String buildSearchResultsCounter(int index, int count, boolean knownIndex, boolean knownCount) {
      return "results";
    }
    public void onSearchUpdateTotalCount(int index, int count, boolean knownIndex, boolean knownCount) {
      controller.onChatSearchFinished("results", index, count);
    }
    public void onAwaitNext(boolean next) {}
    public void onTryToLoadPrevious() {}
    public void onTryToLoadNext() {}
  }
  static class Destroyable {void performDestroy() {}}
  static class Text {void setText(String text) {}}
  static class Badge {
    void setEnabled(boolean enabled, boolean animated) {}
    void setInProgress(boolean loading) {}
  }
  static class Animator {void setValue(boolean value, boolean animated) {}}
  static class Button {void setColorFilter(int color) {}}
  static class Layout {
    Object getLayoutParams() {return new RelativeLayout.LayoutParams();}
    void setLayoutParams(Object params) {}
  }
  static class RelativeLayout {
    static final int ALIGN_PARENT_BOTTOM = 1, ALIGN_BOTTOM = 2, ALIGN_TOP = 3;
    static class LayoutParams {
      int height;
      void addRule(int rule, int anchor) {}
      void addRule(int rule) {}
    }
  }
  static class ViewGroup {static class LayoutParams {static final int MATCH_PARENT = -1;}}
  static class Screen {static int dp(float value) {return (int) value;}}
  static class R {static class id {static final int msg_bottom = 1;}}
  static class ColorId {static final int iconActive = 1, icon = 2;}
  static class Theme {static int getColor(int id) {return id;}}
  static class Lang {static String getXofY(int x, int y) {return "results";}}
  static class Log {
    static final int TAG_MESSAGES_LOADER = 1;
    static boolean isEnabled(int tag) {return false;}
    static void i(int tag, String format, Object... args) {}
  }
  static TdApi.Message message(long chatId, long id) {
    TdApi.Message message = new TdApi.Message(); message.chatId = chatId; message.id = id;
    return message;
  }
  static void respond(Manager manager, int request, long chatId, boolean secret) {
    TdApi.Message[] messages = {message(chatId, 77)};
    manager.searchMiddleware.handlers.get(request).onResult(secret ?
      new TdApi.FoundMessages(1, messages, "") : new TdApi.FoundChatMessages(1, messages, 0));
  }
  static void search(Manager manager, long chatId, boolean secret) {
    manager.searchManager.search(chatId, null, null, null, secret, "query", null);
  }
  static void check(boolean condition, String reason) {
    if (!condition) throw new AssertionError(reason);
  }
  public static void main(String[] args) {
    String scenario = args[0];
    Controller controller = new Controller(); controller.chat.id = 10;
    Manager manager = controller.manager;
    manager.highlightMessageId = new MessageId(10, 99);
    switch (scenario) {
      case "foreign-return-anchor": {
        controller.chat.id = manager.loader.chatId = 20;
        manager.highlightMessageId = new MessageId(20, 99);
        controller.searchFromUserMessageId = new MessageId(10, 41);
        controller.searchMessagesFilterMode = true;
        controller.onLeaveSearchMode();
        check(manager.requestedChat == 20 && manager.highlightMessageId.getChatId() == 20,
          "Closing search in chat B must never restore chat A's sender-search history");
        break;
      }
      case "reset-return-anchor": {
        controller.searchFromUserMessageId = new MessageId(10, 41);
        controller.resetSearchControls(false);
        check(controller.searchFromUserMessageId == null,
          "Resetting a reusable screen must discard the previous sender-search return");
        break;
      }
      case "return-anchor-once":
      case "ordinary-search-after-sender": {
        controller.searchFromUserMessageId = new MessageId(10, 41);
        controller.searchMessagesFilterMode = true;
        controller.onLeaveSearchMode();
        check(manager.highlightMessageId.getMessageId() == 41 && manager.requestedChat == 10,
          "Closing sender search still returns to the original message in the active chat");
        if (scenario.equals("return-anchor-once")) {
          check(controller.searchFromUserMessageId == null, "Consume the sender-search return once");
        } else {
          manager.highlightMessageId = new MessageId(10, 99);
          controller.searchMessagesFilterMode = true;
          controller.onLeaveSearchMode();
          check(manager.highlightMessageId.getMessageId() == 99,
            "A later ordinary search must retain its own selected message");
        }
        break;
      }
      case "foreign-search-callback": {
        manager.loader.chatId = controller.chat.id = 20;
        manager.showSearchResult(0, 1, true, true, new MessageId(10, 77));
        check(manager.navigations.isEmpty() && controller.completedSearches == 0,
          "Reject a foreign search result before updating or navigating the current chat");
        break;
      }
      case "delayed-request-after-destroy": {
        search(manager, 10, false);
        manager.destroy(controller);
        manager.loader.chatId = controller.chat.id = 20;
        UI.flush();
        check(manager.searchMiddleware.handlers.isEmpty(),
          "Destroying a chat must cancel its delayed search request before reuse");
        break;
      }
      case "late-result-after-destroy":
      case "queued-result-after-destroy":
      case "same-chat-stale-result":
      case "secret-result-after-destroy": {
        boolean secret = scenario.equals("secret-result-after-destroy");
        search(manager, 10, secret);
        UI.flush();
        if (scenario.equals("queued-result-after-destroy")) respond(manager, 0, 10, secret);
        manager.destroy(controller);
        manager.loader.chatId = controller.chat.id = scenario.equals("same-chat-stale-result") ? 10 : 20;
        if (!scenario.equals("queued-result-after-destroy")) respond(manager, 0, 10, secret);
        UI.flush();
        check(manager.navigations.isEmpty() &&
          !manager.searchManager.isMessageFound(message(10, 77)) && controller.completedSearches == 0,
          "Pending results must not survive destruction, even when reusing the same chat ID");
        break;
      }
      case "new-search-after-reuse": {
        search(manager, 10, false);
        UI.flush();
        manager.destroy(controller);
        manager.loader.chatId = controller.chat.id = 20;
        search(manager, 20, false);
        UI.flush();
        respond(manager, 1, 20, false);
        respond(manager, 0, 10, false);
        UI.flush();
        check(manager.navigations.size() == 1 && manager.navigations.get(0).getChatId() == 20 &&
          manager.searchManager.isMessageFound(message(20, 77)) &&
          !manager.searchManager.isMessageFound(message(10, 77)),
          "Reused screens must accept the new search and reject old results with the same ID");
        break;
      }
      case "active-search": {
        search(manager, 10, false);
        UI.flush();
        respond(manager, 0, 10, false);
        UI.flush();
        check(manager.navigations.size() == 1 && manager.navigations.get(0).getChatId() == 10 &&
          manager.searchManager.isMessageFound(message(10, 77)), "Active search remains functional");
        break;
      }
      case "queued-count-after-reuse":
      case "queued-count-same-query": {
        search(manager, 10, false);
        UI.flush();
        manager.searchMiddleware.queueTotalCount("10", 99);
        if (scenario.equals("queued-count-after-reuse")) {
          manager.destroy(controller);
          manager.loader.chatId = controller.chat.id = 20;
        }
        search(manager, manager.loader.chatId, false);
        UI.flush();
        check(controller.completedSearches == 0,
          "A queued count notification must not be redirected into a new search session");
        break;
      }
      case "active-count": {
        search(manager, 10, false);
        UI.flush();
        manager.searchMiddleware.queueTotalCount("10", 99);
        UI.flush();
        check(controller.completedSearches == 1, "Active search count updates remain functional");
        break;
      }
      case "retained-count-callback": {
        search(manager, 10, false);
        UI.flush();
        MessagesSearchManagerMiddleware.Delegate old = manager.searchMiddleware.delegate;
        manager.onDestroySearch();
        old.onUpdateTotalCount(99);
        check(controller.completedSearches == 0, "A dismissed search must ignore retained callbacks");
        break;
      }
      default: throw new AssertionError("Unknown scenario: " + scenario);
    }
  }
}
'''.replace("CONTROLLER_METHODS", controller_methods).replace("MANAGER_METHODS", manager_methods)
    stubs = {
      "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
      "androidx/annotation/IntDef.java": "package androidx.annotation; public @interface IntDef {"
        "long[] value() default {}; boolean flag() default false;}",
      "org/jetbrains/annotations/Nullable.java":
        "package org.jetbrains.annotations; public @interface Nullable {}",
      "tgx/td/MessageId.java": r'''
package tgx.td;
public class MessageId {
  private final long chatId, messageId;
  public MessageId(long chatId, long messageId) {this.chatId = chatId; this.messageId = messageId;}
  public long getChatId() {return chatId;}
  public long getMessageId() {return messageId;}
}
''',
      "me/vkryl/core/lambda/CancellableRunnable.java": r'''
package me.vkryl.core.lambda;
public abstract class CancellableRunnable implements Runnable {
  private boolean cancelled;
  public abstract void act();
  public void cancel() {cancelled = true;}
  public void run() {if (!cancelled) act();}
}
''',
      "androidx/collection/LongSparseArray.java": r'''
package androidx.collection;
import java.util.*;
public class LongSparseArray<T> {
  private final TreeMap<Long, T> values = new TreeMap<>();
  public void append(long id, T value) {values.put(id, value);}
  public T get(long id) {return values.get(id);}
  public boolean containsKey(long id) {return values.containsKey(id);}
  public void clear() {values.clear();}
  public int size() {return values.size();}
  public boolean isEmpty() {return values.isEmpty();}
  public int indexOfKey(long id) {return new ArrayList<>(values.keySet()).indexOf(id);}
  public T valueAt(int index) {return new ArrayList<>(values.values()).get(index);}
}
''',
      "org/thunderdog/challegram/telegram/Tdlib.java": r'''
package org.thunderdog.challegram.telegram;
import org.drinkless.tdlib.TdApi;
public class Tdlib {
  public boolean isUserChat(long chatId) {return true;}
  public boolean isChannelChat(TdApi.Chat chat) {return false;}
  public Tdlib settings() {return this;}
  public Tdlib ui() {return this;}
  public void post(Runnable callback) {org.thunderdog.challegram.tool.UI.post(callback);}
  public void removePinnedMessageDismissListener(Object listener) {}
  public void closeChat(long chatId, Object context, boolean force) {}
}
''',
      "org/thunderdog/challegram/tool/UI.java": r'''
package org.thunderdog.challegram.tool;
import java.util.*;
import org.drinkless.tdlib.TdApi;
public class UI {
  private static final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
  public static void post(Runnable callback) {callbacks.add(callback);}
  public static void post(Runnable callback, int delay) {post(callback);}
  public static void showError(TdApi.Object error) {throw new AssertionError("Unexpected error");}
  public static void flush() {while (!callbacks.isEmpty()) callbacks.remove().run();}
}
''',
      "org/thunderdog/challegram/component/chat/MessagesSearchManagerMiddleware.java": r'''
package org.thunderdog.challegram.component.chat;
import java.util.*;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;
public class MessagesSearchManagerMiddleware {
  public interface Delegate {void onUpdateTotalCount(int count);}
  public final List<Client.ResultHandler> handlers = new ArrayList<>();
  public Delegate delegate;
  private final Tdlib tdlib = new Tdlib();
  private String currentContextId;
  private int discardedCount, totalCount = -1;
  static class SendSearchRequestArguments {
    String contextId;
    TdApi.Message[] foundMessages = new TdApi.Message[0];
    SendSearchRequestArguments(String contextId) {this.contextId = contextId;}
  }
  COUNTER
  public void queueTotalCount(String contextId, int count) {
    getTotalCount(new SendSearchRequestArguments(contextId), count);
  }
  public void setDelegate(Delegate delegate) {this.delegate = delegate;}
  public void dismiss() {delegate = null; currentContextId = null; totalCount = -1;}
  public int getCachedMessagesCount() {return 0;}
  public static boolean isFilterPolyfill(TdApi.SearchMessagesFilter filter) {return false;}
  public void search(TdApi.SearchChatMessages query, Client.ResultHandler handler) {
    currentContextId = Long.toString(query.chatId); handlers.add(handler);
  }
  public void search(TdApi.SearchSecretMessages query, TdApi.MessageSender sender,
                     Client.ResultHandler handler) {
    currentContextId = Long.toString(query.chatId); handlers.add(handler);
  }
}
'''.replace("COUNTER", middleware_counter),
    }
    with tempfile.TemporaryDirectory(prefix="search-chat-isolation-") as temp:
      work = Path(temp)
      sources = []
      for name, content in stubs.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(path)
      test = work / "SearchChatIsolationTest.java"
      test.write_text(harness)
      subprocess.run([
        "javac", "-encoding", "UTF-8", "-d", str(work), *map(str, sources),
        str(ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java"),
        str(ROOT / "tdlib/src/main/java/org/drinkless/tdlib/Client.java"),
        str(ROOT / CHAT / "MessagesSearchManager.java"), str(test),
      ], check=True)
      for scenario in (
        "foreign-return-anchor", "reset-return-anchor", "return-anchor-once",
        "ordinary-search-after-sender", "foreign-search-callback", "delayed-request-after-destroy",
        "late-result-after-destroy", "queued-result-after-destroy", "same-chat-stale-result",
        "secret-result-after-destroy", "new-search-after-reuse", "active-search",
        "queued-count-after-reuse", "queued-count-same-query", "active-count",
        "retained-count-callback",
      ):
        with self.subTest(scenario=scenario):
          subprocess.run(["java", "-cp", str(work), "SearchChatIsolationTest", scenario], check=True)


if __name__ == "__main__":
  unittest.main()
