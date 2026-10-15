"""Exercise production history pagination and queued callbacks without Android."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class MessageHistoryPaginationTest(unittest.TestCase):
    def test_history_pages_and_failures(self):
        source = read(
            "app/src/main/java/org/thunderdog/challegram/component/chat/MessagesLoader.java"
        )
        methods = "\n".join(java_method(source, signature) for signature in (
            "private void processMessages (",
            "public boolean loadMore (boolean fromTop)",
            "private boolean loadMore (boolean fromTop, int count, boolean onlyLocal)",
            "public boolean canLoadTop ()",
            "public boolean canLoadBottom ()",
            "private MessageId getStartBottom ()",
            "private MessageId getStartTop ()",
            "private boolean isEndReached ()",
            "private boolean isEndReached (MessageId messageId)",
            "private boolean isLoadingFromThreadStart (MessageId messageId)",
        ))
        begin = source.index("case TdApi.Error.CONSTRUCTOR: {")
        error = source[source.index("{", begin) + 1:
                       source.index("messages = new TdApi.Message[0];", begin)]
        manager_source = read(
            "app/src/main/java/org/thunderdog/challegram/component/chat/MessagesManager.java"
        )
        manager_methods = "\n".join(java_method(manager_source, signature) for signature in (
            "public void checkScrollToBottomButton ()",
            "private void updateNewMessage (TGMessage message)",
            "public void onDestroySearch ()",
        ))
        harness = r'''
import java.util.*;
import org.drinkless.tdlib.TdApi;

public class HistoryPaginationTest {
  @interface Nullable {}
  static final int MODE_INITIAL = 0, MODE_MORE_TOP = 1, MODE_MORE_BOTTOM = 2;
  static final int MODE_REPEAT_INITIAL = 3;
  static final int SPECIAL_MODE_NONE = 0, SPECIAL_MODE_SEARCH = 2;
  static final int SPECIAL_MODE_RESTRICTED = 3, SPECIAL_MODE_SCHEDULED = 4;
  static final int MERGE_MODE_NONE = 0;
  static final int CHUNK_SIZE_SMALL = 19, CHUNK_SIZE_SEARCH = 33;
  static final int CHUNK_SIZE_BIG = 50, CHUNK_SIZE_BOTTOM = 31, CHUNK_BOTTOM_OFFSET = -30;
  final Object lock = new Object();
  final MessagesManager manager = new MessagesManager(this);
  final TdApi.Chat chat = new TdApi.Chat();
  final List<MessageId> requests = new ArrayList<>();
  ThreadInfo messageThread;
  MessageId scrollMessageId, lastFromMessageId;
  Object lastHandler;
  TdApi.SearchMessagesFilter searchFilter;
  int loadingMode, specialMode, scrollHighlightMode, mergeMode, lastOffset, lastLimit;
  long contextId = 1, lastSearchNextFromMessageId, lastMessageId = 100;
  String lastSearchNextOffset;
  boolean canLoadTop = true, canLoadBottom = true, isLoading = true, loadingLocal;
  boolean loadingAllowMoreTop, loadingAllowMoreBottom, foundUnreadAtLeastOnce;
  boolean convertedErrorToEmpty;
  METHODS
  void handleError(TdApi.Object object, long currentContextId) {
    ERROR
    convertedErrorToEmpty = true;
  }
  HistoryPaginationTest(int mode) {
    chat.id = 10;
    loadingMode = mode;
    manager.adapter.rows.add(new TGMessage(message(50, 7)));
  }
  void respond(TdApi.Message... messages) {
    isLoading = true;
    lastHandler = null;
    processMessages(contextId, messages, -1, null, 0, false, null);
  }
  long getChatId() {return chat.id;}
  long getLastMessageId() {return lastMessageId;}
  int getSpecialMode() {return specialMode;}
  ThreadInfo getMessageThread() {return messageThread;}
  TdApi.MessageTopic getMessageTopicId() {return null;}
  boolean isForumTopic() {return false;}
  TdApi.ForumTopic getForumTopic() {return null;}
  boolean hasSearchFilter() {return searchFilter != null;}
  void setKnownTotalMessageCount(int count) {}
  void fetchAlbum(List<TdApi.Message> album) {throw new AssertionError("Unexpected album fetch");}
  void startMeasureStep() {}
  void endMeasureStep(TGMessage message, long id, int size) {}
  void completeMeasure() {}
  void load(MessageId anchor, int offset, int limit, int mode, boolean local,
            boolean allowTop, boolean allowBottom) {
    check(!isLoading, "Release the loading lock before continuing pagination");
    requests.add(anchor);
    loadingMode = mode;
    loadingLocal = local;
    isLoading = true;
    lastHandler = new Object();
  }
  static class MessageId {
    final long chatId, id;
    MessageId(long chatId, long id) {this.chatId = chatId; this.id = id;}
    long getChatId() {return chatId;}
    long getMessageId() {return id;}
    boolean isHistoryEnd() {return id == 0;}
    boolean isHistoryStart() {return id == -1;}
    boolean compareTo(long chatId, long id) {return this.chatId == chatId && this.id == id;}
  }
  static class TGMessage {
    final List<TdApi.Message> messages = new ArrayList<>();
    TGMessage(TdApi.Message message) {messages.add(message);}
    static TGMessage valueOf(MessagesManager manager, TdApi.Message message, TdApi.Chat chat,
                             ThreadInfo thread, TdApi.ChatAdministrator admin) {
      return new TGMessage(message);
    }
    boolean wouldCombineWith(TdApi.Message other) {
      return other.mediaAlbumId != 0 && other.mediaAlbumId == messages.get(0).mediaAlbumId;
    }
    boolean combineWith(TdApi.Message message, boolean bottom) {
      if (!wouldCombineWith(message)) return false;
      check(messages.stream().noneMatch(item -> item.id == message.id), "Do not duplicate anchors");
      messages.add(message);
      return true;
    }
    long getChatId() {return messages.get(0).chatId;}
    long getBiggestId() {return messages.stream().mapToLong(message -> message.id).max().getAsLong();}
    long getSmallestId() {return messages.stream().mapToLong(message -> message.id).min().getAsLong();}
    long getId() {return getBiggestId();}
    int getMessageCount() {return messages.size();}
    MessageId toMessageId() {return new MessageId(getChatId(), getId());}
    TdApi.Message getMessage() {return messages.get(0);}
    TdApi.MessageTopic getMessageTopicId() {return null;}
    boolean isOutgoing() {return false;}
    boolean isSending() {return false;}
    boolean isOld() {return false;}
    boolean isChatMember() {return false;}
    boolean isSponsoredMessage() {return false;}
    void markAsUnread() {}
    void invalidateContentReceiver(long id, int type) {}
    void onDestroy() {}
    void setUnread(long id) {}
    void setShowUnreadBadge(boolean show) {}
    void mergeWith(TGMessage other, boolean end) {}
    void prepareLayout() {}
    void highlight(boolean animate) {}
  }
  static class Adapter {
    final List<TGMessage> rows = new ArrayList<>();
    TGMessage getBottomActiveMessage() {return rows.isEmpty() ? null : rows.get(0);}
    TGMessage getTopActiveMessage() {return rows.isEmpty() ? null : rows.get(rows.size() - 1);}
    TGMessage getBottomMessage() {return getBottomActiveMessage();}
    boolean addMessage(TGMessage message, boolean top, boolean scroll) {
      rows.add(top ? rows.size() : 0, message);
      return false;
    }
  }
  static class MessagesLoader {
    static final int SPECIAL_MODE_RESTRICTED = HistoryPaginationTest.SPECIAL_MODE_RESTRICTED;
    static final int SPECIAL_MODE_SEARCH = HistoryPaginationTest.SPECIAL_MODE_SEARCH;
  }
  static class MessagesManager {
    static final int HIGHLIGHT_MODE_NONE = 0, HIGHLIGHT_MODE_NORMAL = 1, HIGHLIGHT_MODE_UNREAD = 2;
    static final int HIGHLIGHT_MODE_POSITION_RESTORE = 3;
    static final int HIGHLIGHT_MODE_UNREAD_NEXT = 4, HIGHLIGHT_MODE_NORMAL_NEXT = 5;
    final Adapter adapter = new Adapter();
    final LayoutManager manager = new LayoutManager();
    final Controller controller = new Controller();
    final SearchManager searchManager = new SearchManager();
    final HistoryPaginationTest loader;
    TdApi.ChatEventLogFilters eventLogFilters;
    long[] eventLogUserIds;
    boolean lastScrollToBottomVisible;
    int topEnds, bottomEnds, checkedEnds, networkRequests;
    MessagesManager(HistoryPaginationTest loader) {this.loader = loader;}
    MANAGER_METHODS
    int getActiveMessageCount() {return adapter.rows.size();}
    boolean isReturnAbove() {return false;}
    boolean hasReturnMessage() {return false;}
    boolean isAtVeryBottom() {return false;}
    boolean isEventLog() {return false;}
    void applyEventLogFilters(TdApi.ChatEventLogFilters filters, String query, long[] ids) {
      throw new AssertionError("Ordinary search must not change event log filters");
    }
    void revokeReturnMessages() {}
    void scrollToBottom(boolean animated) {}
    void scrollToPositionWithOffsetImpl(int position, int offset) {}
    void loadFromStart() {throw new AssertionError("Unexpected jump to latest history");}
    int calculateOffsetInPixels(View view, int padding) {return 0;}
    int getExtraScrollSpacing() {return 0;}
    Adapter getAdapter() {return adapter;}
    LongSparseArray<TdApi.ChatAdministrator> getChatAdmins() {return null;}
    long maxPinnedMessageId() {return 0;}
    void onTopEndLoaded() {topEnds++;}
    void onBottomEndLoaded() {bottomEnds++;}
    void onBottomEndChecked() {checkedEnds++;}
    void onNetworkRequestSent() {networkRequests++;}
    void ensureContentHeight() {}
    void displayMessages(ArrayList<TGMessage> items, int mode, int position, TGMessage scroll,
                         MessageId id, int highlight, boolean repeat, boolean canLoadTop) {
      if (mode == MODE_INITIAL || mode == MODE_REPEAT_INITIAL) adapter.rows.clear();
      if (mode == MODE_MORE_BOTTOM) adapter.rows.addAll(0, items);
      else adapter.rows.addAll(items);
    }
  }
  static class View {Object getParent() {return null;}}
  static class SearchManager {
    boolean dismissed;
    void onDismiss() {dismissed = true;}
  }
  static class LayoutManager {
    int findFirstVisibleItemPosition() {return 5;}
    int findFirstCompletelyVisibleItemPosition() {return 5;}
    View findViewByPosition(int position) {return null;}
    void scrollToPosition(int position) {}
  }
  static class Controller {
    boolean scrollToBottomVisible;
    void checkSwitchPm(TdApi.Message message) {}
    boolean canWriteMessages() {return true;}
    void setScrollToBottomVisible(boolean visible, boolean reverse) {
      scrollToBottomVisible = visible;
    }
  }
  static class ThreadInfo {
    long getChatId() {return 10;}
    long getLastReadOutboxMessageId() {return 0;}
    long getLastReadInboxMessageId() {return 0;}
    long getOldestMessageId() {return 1;}
    long getNewestMessageId() {return 2;}
    boolean hasUnreadMessages(TdApi.Chat chat) {return false;}
    void updateNewMessage(TGMessage message) {}
  }
  static class LongSparseArray<T> {T get(long id) {return null;}}
  static class Td {
    static long getSenderUserId(TdApi.Message message) {return 0;}
    static boolean matchesTopic(TdApi.MessageTopic first, TdApi.MessageTopic second) {
      return first == second;
    }
    static boolean isPinnedFilter(TdApi.SearchMessagesFilter filter) {
      return filter instanceof TdApi.SearchMessagesFilterPinned;
    }
  }
  static class TD {
    static boolean isSupergroup(TdApi.ChatType type) {return false;}
    static boolean isChannel(TdApi.ChatType type) {return false;}
    static String toErrorString(TdApi.Object object) {return "History unavailable";}
  }
  static class Log {
    static final int TAG_MESSAGES_LOADER = 1, LEVEL_INFO = 1;
    static boolean isEnabled(int tag) {return false;}
    static boolean checkLogLevel(int level) {return false;}
    static void i(int tag, String format, Object... args) {}
    static void v(int tag, String format, Object... args) {}
    static void w(int tag, String format, Object... args) {}
    static void critical(String message, Throwable error) {throw new AssertionError(message, error);}
  }
  static class UI {
    static final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
    static int errors;
    static void post(Runnable callback) {callbacks.add(callback);}
    static void showError(TdApi.Object error) {errors++;}
    static void flush() {while (!callbacks.isEmpty()) callbacks.remove().run();}
  }
  static TdApi.Message message(long id, long album) {
    TdApi.Message message = new TdApi.Message();
    message.id = id; message.chatId = 10; message.mediaAlbumId = album;
    return message;
  }
  static void check(boolean value, String reason) {if (!value) throw new AssertionError(reason);}
  public static void main(String[] args) {
    String scenario = args[0];
    boolean older = scenario.endsWith("older");
    HistoryPaginationTest loader = new HistoryPaginationTest(older ? MODE_MORE_TOP : MODE_MORE_BOTTOM);
    TGMessage album = loader.manager.adapter.rows.get(0);
    switch (scenario) {
      case "search-history-race":
      case "search-live-message-race":
      case "dismissed-search-history-race": {
        loader.manager.adapter.rows.clear();
        loader.manager.adapter.rows.add(new TGMessage(message(100, 0)));
        loader.loadingMode = MODE_REPEAT_INITIAL;
        loader.scrollMessageId = new MessageId(10, 50);
        loader.scrollHighlightMode = MessagesManager.HIGHLIGHT_MODE_NORMAL;
        TdApi.Message[] slice = new TdApi.Message[33];
        for (int i = 0; i < slice.length; i++) slice[i] = message(65 - i, 0);
        // The TDLib callback finishes while RecyclerView still displays the latest history.
        loader.respond(slice);
        check(loader.manager.adapter.getBottomActiveMessage().getId() == 100,
          "The old list stays visible until the queued history replacement runs");
        loader.manager.checkScrollToBottomButton();
        UI.flush();
        if (scenario.equals("dismissed-search-history-race")) {
          loader.manager.onDestroySearch();
          check(loader.manager.searchManager.dismissed, "Close the search UI");
        }
        if (!scenario.equals("search-history-race")) {
          loader.lastMessageId = 101;
          loader.manager.updateNewMessage(new TGMessage(message(101, 0)));
          check(loader.manager.adapter.getBottomActiveMessage().getId() == 65,
            "Live messages must not be spliced across the gap after a search jump");
        }
        check(loader.canLoadBottom() && loader.manager.bottomEnds == 0,
          "Checking the old list during replacement must not close the new history slice");
        check(loader.loadMore(false) && loader.requests.get(0).id == 65,
          "Forward pagination must continue from the search history boundary");
        loader.respond(message(67, 0), message(66, 0), message(65, 0));
        UI.flush();
        check(loader.manager.adapter.getBottomActiveMessage().getId() == 67,
          "The messages immediately following the search slice remain reachable");
        break;
      }
      case "current-history-live-message": {
        loader.manager.adapter.rows.clear();
        loader.manager.adapter.rows.add(new TGMessage(message(100, 0)));
        loader.isLoading = false;
        check(!loader.canLoadBottom(), "An installed history slice may confirm the real end");
        loader.lastMessageId = 101;
        loader.manager.updateNewMessage(new TGMessage(message(101, 0)));
        check(loader.manager.adapter.rows.size() == 2 &&
          loader.manager.adapter.getBottomActiveMessage().getId() == 101,
          "Live messages still append normally when the history is current");
        break;
      }
      case "album-newer":
      case "album-older": {
        loader.respond(older ? message(50, 7) : message(52, 7),
          older ? message(49, 7) : message(51, 7), older ? message(48, 7) : message(50, 7));
        UI.flush();
        check(loader.canLoadTop && loader.canLoadBottom && loader.manager.topEnds == 0 &&
          loader.manager.bottomEnds == 0, "Album completion must not mark a history end");
        check(album.getMessageCount() == 3, "Merge all newly loaded photos");
        check(loader.requests.size() == 1 && loader.requests.get(0).id == (older ? 48 : 52),
          "Continue beyond an album-only page using its updated boundary");
        loader.respond(older ? message(48, 7) : message(60, 0),
          older ? message(40, 0) : message(52, 7));
        UI.flush();
        check(loader.manager.adapter.rows.size() == 2 && !loader.isLoading,
          "Messages beyond the album remain reachable");
        break;
      }
      case "mixed-newer": {
        loader.respond(message(60, 0), message(51, 7), message(50, 7));
        UI.flush();
        check(album.getMessageCount() == 2 && loader.manager.adapter.rows.size() == 2 &&
          loader.requests.isEmpty() && loader.canLoadBottom,
          "A page with an album continuation and a new row needs no forced request");
        break;
      }
      case "overlap-newer":
      case "empty-older": {
        loader.respond(older ? new TdApi.Message[0] : new TdApi.Message[] {message(50, 7)});
        UI.flush();
        check(!(older ? loader.canLoadTop : loader.canLoadBottom) && !loader.isLoading &&
          loader.requests.isEmpty(), "An actual exhausted page still ends pagination");
        check(older ? loader.manager.topEnds == 1 : loader.manager.bottomEnds == 1,
          "Report a confirmed history end exactly once");
        break;
      }
      case "last-album": {
        loader.lastMessageId = 51;
        loader.respond(message(51, 7), message(50, 7));
        UI.flush();
        check(album.getBiggestId() == 51 && !loader.canLoadBottom() &&
          loader.requests.isEmpty() && loader.manager.bottomEnds == 1,
          "Stop at the known latest message after merging the final photo");
        break;
      }
      case "stale-album": {
        loader.respond(message(51, 7), message(50, 7));
        loader.contextId++;
        loader.isLoading = true;
        UI.flush();
        check(album.getMessageCount() == 1 && loader.requests.isEmpty() && loader.isLoading,
          "A replaced search/history context cannot apply queued album changes");
        break;
      }
      case "history-error":
      case "search-error":
      case "thread-error": {
        if (scenario.equals("search-error")) {
          loader.specialMode = SPECIAL_MODE_SEARCH;
          loader.searchFilter = new TdApi.SearchMessagesFilterPhoto();
        } else if (scenario.equals("thread-error")) {
          loader.messageThread = new ThreadInfo();
        }
        loader.lastHandler = new Object();
        loader.handleError(new TdApi.Error(500, "Temporary failure"), loader.contextId);
        UI.flush();
        check(!loader.convertedErrorToEmpty && !loader.isLoading && loader.lastHandler == null &&
          loader.canLoadBottom && loader.canLoadTop && UI.errors == 1,
          "A failed request must release loading and preserve both pagination boundaries");
        check(loader.loadMore(false) && loader.requests.size() == 1,
          "Scrolling can retry a failed request");
        break;
      }
      case "album-error": {
        loader.mergeMode = 1;
        loader.handleError(new TdApi.Error(500, "Album cache unavailable"), loader.contextId);
        check(loader.convertedErrorToEmpty && UI.callbacks.isEmpty(),
          "An optional album cache error still lets the original page be processed");
        break;
      }
      case "stale-error": {
        loader.handleError(new TdApi.Error(500, "Old failure"), loader.contextId);
        loader.contextId++;
        loader.lastHandler = new Object();
        UI.flush();
        check(loader.isLoading && loader.lastHandler != null && UI.errors == 0,
          "An old failure cannot release a newer request's loading state");
        break;
      }
      default: throw new AssertionError("Unknown scenario: " + scenario);
    }
  }
}
'''.replace("MANAGER_METHODS", manager_methods).replace("METHODS", methods).replace("ERROR", error)
        with tempfile.TemporaryDirectory(prefix="message-history-") as temp:
            work = Path(temp)
            annotations = []
            for name, body in (
                ("Nullable", "public @interface Nullable {}"),
                ("IntDef", "public @interface IntDef {long[] value() default {}; "
                           "boolean flag() default false;}"),
            ):
                path = work / "androidx/annotation" / f"{name}.java"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package androidx.annotation; " + body)
                annotations.append(path)
            test = work / "HistoryPaginationTest.java"
            test.write_text(harness)
            subprocess.run([
                "javac", "-encoding", "UTF-8", "-d", str(work), *map(str, annotations),
                str(ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java"), str(test),
            ], check=True)
            for scenario in (
                "search-history-race", "search-live-message-race", "dismissed-search-history-race",
                "current-history-live-message",
                "album-newer", "album-older", "mixed-newer", "overlap-newer", "empty-older",
                "last-album", "stale-album", "history-error", "search-error", "thread-error",
                "album-error", "stale-error",
            ):
                with self.subTest(scenario=scenario):
                    subprocess.run([
                        "java", "-cp", str(work), "HistoryPaginationTest", scenario,
                    ], check=True)


if __name__ == "__main__":
    unittest.main()
