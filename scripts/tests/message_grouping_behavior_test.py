"""Exercise live message grouping after TDLib corrects send timestamps."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class MessageGroupingBehaviorTest(unittest.TestCase):
    def test_send_updates_match_reopened_history(self):
        message = read("app/src/main/java/org/thunderdog/challegram/data/TGMessage.java")
        adapter = read("app/src/main/java/org/thunderdog/challegram/component/chat/MessagesAdapter.java")
        manager = read("app/src/main/java/org/thunderdog/challegram/component/chat/MessagesManager.java")
        constants = "\n".join(re.findall(
            r"(?:private|public) static final int (?:MAXIMUM_\w+|MESSAGE_FLAG_\w+|"
            r"FLAG_\w+|MESSAGE_NOT_CHANGED|MESSAGE_INVALIDATED|MESSAGE_CHANGED|"
            r"MESSAGE_REPLACE_REQUIRED) = [^;]+;", message))
        message_methods = "\n".join(java_method(message, signature) for signature in (
            "public final boolean mergeWith (", "public int getMergeIndex ()",
            "public int getMergeTime ()", "public boolean hasHeader ()",
            "boolean isBottomMessage ()", "public void setIsBottom (",
            "private void setHeaderEnabled (", "public final int setSendSucceeded (",
            "private static void copyFlags (", "public boolean setSendFailed (",
        )).replace("@Nullable ", "")
        adapter_methods = "\n".join(java_method(adapter, signature) for signature in (
            "public TGMessage getMessage (int index)", "public TGMessage getItem (int index)",
            "public int getMessageCount ()", "public void replaceItem (",
            "public int indexOfMessageContainer (long messageId)",
        ))
        # The baseline has no refresh helper; its real send callbacks must still run and fail.
        if "public void updateMessageMerging (" in adapter:
            adapter_methods += "\n" + java_method(adapter, "public void updateMessageMerging (")
        manager_methods = "\n".join(java_method(manager, signature) for signature in (
            "private void updateMessageSendSucceeded (", "private void updateMessageSendFailed (",
        ))
        harness = r'''
import java.util.*;
import org.drinkless.tdlib.TdApi;
public class MessageGroupingTest {
  static class BitwiseUtils {
    static int setFlag(int flags, int flag, boolean enabled) {
      return enabled ? flags | flag : flags & ~flag;
    }
    static boolean hasFlag(int flags, int flag) { return (flags & flag) != 0; }
  }
  static class StringUtils {
    static boolean equalsOrBothEmpty(String a, String b) {
      return Objects.equals(a == null ? "" : a, b == null ? "" : b);
    }
  }
  static class DateUtils {
    static boolean isSameDay(int a, int b) { return a / 86400 == b / 86400; }
    static boolean isSameHour(int a, int b) { return a / 3600 == b / 3600; }
  }
  static class TD {
    static boolean isSameSource(TdApi.Message a, TdApi.Message b, boolean force) {
      return a.forwardInfo == b.forwardInfo;
    }
  }
  static class Td {
    static boolean equalsTo(Object a, Object b) { return Objects.equals(a, b); }
  }
  static class Tdlib {
    boolean isSameSender(TdApi.Message a, TdApi.Message b) {
      return ((TdApi.MessageSenderUser) a.senderId).userId ==
             ((TdApi.MessageSenderUser) b.senderId).userId;
    }
  }
  static class Controller {
    boolean scheduled;
    boolean areScheduledOnly() { return scheduled; }
  }
  static class Keyboard { void updateMessageId(long oldId, long newId) {} }
  static class TGMessage {
    CONSTANTS
    TdApi.Message msg;
    final Tdlib tdlib = new Tdlib();
    final Controller controller = new Controller();
    ArrayList<TdApi.Message> combinedMessages;
    Keyboard inlineKeyboard;
    int flags = FLAG_LAYOUT_BUILT, mergeTime, mergeIndex, height = 1, rebuilds;
    boolean bubbles = true, changeHeight, drawnTail;
    TGMessage(TdApi.Message msg) { this.msg = msg; }
    TdApi.Message getMessage() { return msg; }
    TdApi.Message getMessage(long id) { return msg.id == id ? msg : null; }
    boolean isDescendantOrSelf(long id) { return msg.id == id; }
    Controller messagesController() { return controller; }
    boolean useBubbles() { return bubbles; }
    boolean isChannel() { return false; }
    boolean isEventLog() { return false; }
    boolean isThreadHeader() { return false; }
    boolean isSponsoredMessage() { return false; }
    boolean needHideEventDate() { return false; }
    boolean headerDisabled() { return false; }
    boolean mergeDisabled() { return false; }
    boolean forceForwardOrImportInfo() { return false; }
    boolean isOutgoingBubble() { return bubbles && msg.isOutgoing; }
    boolean isOutgoing() { return msg.isOutgoing; }
    boolean isForward() { return msg.forwardInfo != null; }
    boolean isUnread() { return false; }
    boolean isSending() { return msg.sendingState instanceof TdApi.MessageSendingStatePending; }
    boolean isScheduled() { return controller.scheduled; }
    String getAdministratorSign() { return null; }
    int getComparingDate() { return msg.date; }
    int getDate() { return msg.date; }
    String genDate() { return "date"; }
    int getHeight() { return height; }
    int computeHeight() { return height; }
    void setNeedExtraPadding(boolean value) {}
    void setNeedExtraPresponsoredPadding(boolean value) {}
    void updateShowBadge() {}
    void updateBadgeText() {}
    void setDate(String value) {}
    void setMergeBottom(boolean value) {}
    void buildBubble(boolean force) {}
    void rebuildLayout() { rebuilds++; }
    void onDestroy() {}
    boolean isSupportedMessageContent(TdApi.Message msg, TdApi.MessageContent content) {
      return msg.content.getConstructor() == content.getConstructor();
    }
    boolean onMessageContentChanged(TdApi.Message msg, TdApi.MessageContent oldContent,
                                    TdApi.MessageContent content, boolean bottom) {
      if (changeHeight) height++;
      return true;
    }
    void buildTime() {}
    void updateInteractionInfo(boolean animate) {}
    void updateMessageId(long oldId, long newId, boolean succeeded) {}
    void buildMarkup() {}
    void computeQuickButtons() {}
    MESSAGE_METHODS
  }
  static class MessagesAdapter {
    ArrayList<TGMessage> items = new ArrayList<>();
    Set<Integer> notified = new HashSet<>();
    void notifyItemChanged(int index) {
      check(index >= 0 && index < items.size(), "Notification outside the message list");
      notified.add(index);
      items.get(index).drawnTail = items.get(index).isBottomMessage();
    }
    void notifyItemRangeChanged(int first, int count) {
      for (int i = first; i < first + count; i++) notifyItemChanged(i);
    }
    ADAPTER_METHODS
  }
  static class ThreadInfo { void updateReadInbox(TdApi.Message message) {} }
  static class Loader { ThreadInfo getMessageThread() { return null; } }
  static class MessagesManager {
    final MessagesAdapter adapter = new MessagesAdapter();
    final Loader loader = new Loader();
    MessagesAdapter getAdapter() { return adapter; }
    void updateReturnToMessageId(long oldId, long newId) {}
    void invalidateViewAt(int index) { adapter.notifyItemChanged(index); }
    void checkPositionInList(TGMessage msg, int index, long id) {}
    void viewMessages(boolean force) {}
    void replaceMessage(TGMessage msg, int index, long id, TdApi.Message message) {
      TGMessage replacement = new TGMessage(message);
      replacement.bubbles = msg.bubbles;
      replacement.controller.scheduled = msg.controller.scheduled;
      adapter.replaceItem(index, replacement);
    }
    MANAGER_METHODS
  }
  static TdApi.Message message(long id, int date, long sender, boolean sending) {
    TdApi.Message msg = new TdApi.Message();
    msg.id = id; msg.date = date; msg.senderId = new TdApi.MessageSenderUser(sender);
    msg.isOutgoing = true;
    msg.content = new TdApi.MessageText(new TdApi.FormattedText("text", null), null, null);
    msg.sendingState = sending ? new TdApi.MessageSendingStatePending() : null;
    return msg;
  }
  static MessagesManager history(int... dates) {
    MessagesManager manager = new MessagesManager();
    for (int i = 0; i < dates.length; i++)
      manager.adapter.items.add(0, new TGMessage(message(i + 1, dates[i], 1, true)));
    reopen(manager.adapter);
    return manager;
  }
  static void reopen(MessagesAdapter adapter) {
    for (int i = adapter.items.size() - 1; i >= 0; i--)
      adapter.items.get(i).mergeWith(adapter.getMessage(i + 1), i == 0);
    for (int i = 0; i < adapter.items.size(); i++) adapter.notifyItemChanged(i);
    adapter.notified.clear();
    for (TGMessage msg : adapter.items) msg.rebuilds = 0;
  }
  static void check(boolean condition, String reason) {
    if (!condition) throw new AssertionError(reason);
  }
  static void matchesReopened(MessagesAdapter adapter) {
    int[] flags = new int[adapter.items.size()];
    int[] times = new int[flags.length], indices = new int[flags.length];
    for (int i = 0; i < flags.length; i++) {
      TGMessage msg = adapter.items.get(i);
      flags[i] = msg.flags; times[i] = msg.getMergeTime(); indices[i] = msg.getMergeIndex();
      check(msg.drawnTail == msg.isBottomMessage(), "Redraw every affected bubble tail");
    }
    reopen(adapter);
    for (int i = 0; i < flags.length; i++) {
      TGMessage msg = adapter.items.get(i);
      check(flags[i] == msg.flags && times[i] == msg.getMergeTime() &&
            indices[i] == msg.getMergeIndex(), "Live grouping differs from reopened history at " + i);
    }
  }
  public static void main(String[] args) {
    // A temporary timestamp prevents grouping; confirmation must remove the older tail.
    MessagesManager manager = history(100, 2000);
    TGMessage older = manager.adapter.getItem(1), newest = manager.adapter.getItem(0);
    check(newest.hasHeader() && older.drawnTail, "Start with separate bubbles");
    manager.updateMessageSendSucceeded(message(20, 101, 1, false), 2);
    check(!newest.hasHeader() && !older.drawnTail,
      "Send confirmation must join bubbles without closing the chat");
    check(manager.adapter.getItem(0) == newest && manager.adapter.getItem(1) == older,
      "Preserve message instances and order");
    matchesReopened(manager.adapter);

    // A root correction must also update merge timestamps in all of its descendants.
    manager = history(100, 101, 102, 103);
    manager.updateMessageSendSucceeded(message(10, 99, 1, false), 1);
    check(manager.adapter.getItem(0).getMergeTime() == 99, "Propagate corrected group time");
    matchesReopened(manager.adapter);

    // Joining two groups propagates past both immediate neighbors.
    manager = history(100, 2000, 102, 103);
    manager.updateMessageSendSucceeded(message(20, 101, 1, false), 2);
    check(manager.adapter.getItem(0).getMergeIndex() == 3, "Join the complete message group");
    matchesReopened(manager.adapter);

    // The correction can split a group and move a date separator as well as join it.
    manager = history(100, 101, 102, 103);
    manager.updateMessageSendSucceeded(message(20, 90000, 1, false), 2);
    matchesReopened(manager.adapter);

    // Height-changing send completions use the same grouping refresh.
    manager = history(100, 2000);
    manager.adapter.getItem(0).changeHeight = true;
    manager.updateMessageSendSucceeded(message(20, 101, 1, false), 2);
    matchesReopened(manager.adapter);

    // Replacement must establish the new group root before merging newer messages.
    manager = history(100, 2000, 102, 103);
    TdApi.Message replacement = message(20, 101, 1, false);
    replacement.content = new TdApi.MessagePhoto();
    manager.updateMessageSendSucceeded(replacement, 2);
    matchesReopened(manager.adapter);

    // Failure updates also replace metadata and must refresh affected grouping.
    manager = history(100, 2000);
    TdApi.Message failed = message(20, 101, 1, false);
    failed.sendingState = new TdApi.MessageSendingStateFailed();
    manager.updateMessageSendFailed(failed, 2);
    matchesReopened(manager.adapter);

    // Scheduled messages and different senders remain separate; refresh stops there.
    manager = history(100, 2000, 2001, 2002);
    manager.adapter.getItem(1).msg.senderId = new TdApi.MessageSenderUser(2);
    manager.adapter.getItem(0).msg.senderId = new TdApi.MessageSenderUser(2);
    reopen(manager.adapter);
    manager.updateMessageSendSucceeded(message(20, 101, 1, false), 2);
    check(manager.adapter.getItem(1).hasHeader(), "Keep different senders separate");
    check(manager.adapter.getItem(0).rebuilds == 0, "Stop at an unchanged group boundary");
    matchesReopened(manager.adapter);
    manager = history(100, 2000);
    for (TGMessage msg : manager.adapter.items) msg.controller.scheduled = true;
    reopen(manager.adapter);
    manager.updateMessageSendSucceeded(message(20, 101, 1, false), 2);
    check(manager.adapter.getItem(0).hasHeader(), "Keep scheduled message timestamps separate");
    matchesReopened(manager.adapter);

    // Classic mode has finite groups: correcting a root must repair the count boundary.
    manager = history(100, 2000, 102, 103, 104, 105, 106, 107,
      108, 109, 110, 111, 112, 113, 114, 115, 116);
    for (TGMessage msg : manager.adapter.items) msg.bubbles = false;
    reopen(manager.adapter);
    manager.updateMessageSendSucceeded(message(20, 101, 1, false), 2);
    matchesReopened(manager.adapter);

    manager = history(100);
    manager.updateMessageSendSucceeded(message(10, 101, 1, false), 1);
    check(manager.adapter.getItem(0).drawnTail, "Single-message chat keeps its tail");
    matchesReopened(manager.adapter);
    manager.updateMessageSendSucceeded(message(20, 102, 1, false), 999);
    check(manager.adapter.notified.isEmpty(), "Ignore completions outside loaded history");
  }
}
'''
        for marker, methods in (("CONSTANTS", constants), ("MESSAGE_METHODS", message_methods),
                                ("ADAPTER_METHODS", adapter_methods), ("MANAGER_METHODS", manager_methods)):
            harness = harness.replace(marker, methods)
        with tempfile.TemporaryDirectory(prefix="message-grouping-test-") as directory:
            work = Path(directory)
            sources = [ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java"]
            for name, content in {
                "MessageGroupingTest.java": harness,
                "androidx/annotation/Nullable.java":
                    "package androidx.annotation; public @interface Nullable {}",
                "androidx/annotation/IntDef.java":
                    "package androidx.annotation; public @interface IntDef {long[] value() default {};}",
            }.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                sources.append(path)
            result = subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work),
                                     *map(str, sources)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", str(work), "MessageGroupingTest"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
