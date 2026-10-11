"""Exercise immediate message grouping and send metadata corrections."""

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
    def test_live_grouping_matches_reopened_history(self):
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
            "private String getAdministratorSign ()", "private boolean needName (boolean allowVia)",
        )).replace("@Nullable ", "")
        adapter_methods = "\n".join(java_method(adapter, signature) for signature in (
            "public TGMessage getMessage (int index)", "public TGMessage getItem (int index)",
            "public int getMessageCount ()", "public void replaceItem (",
            "public int indexOfMessageContainer (long messageId)",
            "public boolean addMessageImpl (",
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
    static boolean isEmpty(String text) { return text == null || text.isEmpty(); }
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
  static class ChatId {
    static boolean isBasicGroup(long id) { return id == -10; }
    static boolean isMultiChat(long id) { return id < 0; }
  }
  static class R {
    static class string {
      static final int message_ownerSign = 1, message_adminSignPlain = 2, message_groupSign = 3;
    }
  }
  static class Lang { static String getString(int id) { return "label:" + id; } }
  static class Tdlib {
    boolean isSupergroup(long id) { return id == -100; }
    boolean isMultiChat(long id) { return id < 0; }
    boolean isChannel(long id) { return id == -200; }
    TdApi.Chat chat(long id) { return null; }
    boolean isSameSender(TdApi.Message a, TdApi.Message b) {
      if (a.senderId.getConstructor() != b.senderId.getConstructor()) return false;
      if (a.senderId instanceof TdApi.MessageSenderUser)
        return ((TdApi.MessageSenderUser) a.senderId).userId ==
               ((TdApi.MessageSenderUser) b.senderId).userId;
      return ((TdApi.MessageSenderChat) a.senderId).chatId ==
             ((TdApi.MessageSenderChat) b.senderId).chatId;
    }
  }
  static class Controller {
    boolean scheduled;
    boolean areScheduledOnly() { return scheduled; }
    ThreadInfo getMessageThread() { return null; }
    void highlightMessage(Object id) {}
  }
  static class Keyboard { void updateMessageId(long oldId, long newId) {} }
  static class TGMessage implements Comparable<TGMessage> {
    CONSTANTS
    TdApi.Message msg;
    TdApi.Chat chat;
    TdApi.ChatAdministrator administrator;
    String memberTag;
    class Sender {
      boolean isUser() { return msg.senderId instanceof TdApi.MessageSenderUser; }
      boolean isAnonymousGroupAdmin() { return !isUser() && getChatId() == msg.chatId; }
      boolean isChannel() { return !isUser() && !isAnonymousGroupAdmin(); }
      long getChatId() { return isUser() ? 0 : ((TdApi.MessageSenderChat) msg.senderId).chatId; }
    }
    final Sender sender = new Sender();
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
    Tdlib tdlib() { return tdlib; }
    boolean useBubbles() { return bubbles; }
    boolean useBubble() { return bubbles; }
    boolean useForward() { return isForward(); }
    boolean separateReplyFromBubble() { return false; }
    boolean isDemoGroupChat() { return false; }
    boolean isPsa() { return false; }
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
    long getId() { return msg.id; }
    long getChatId() { return msg.chatId; }
    Object toMessageId() { return msg.id; }
    boolean isOld() { return false; }
    boolean isNotSent() { return msg.sendingState != null; }
    boolean checkIsUnread(boolean value) { return false; }
    void markAsBeingAdded(boolean value) {}
    void setShowUnreadBadge(boolean value) {}
    public int compareTo(TGMessage other) { return Long.compare(other.msg.id, msg.id); }
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
    void prepareLayout() { rebuildLayout(); }
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
    static final int INITIAL_CAPACITY = 20;
    MessagesManager manager;
    ArrayList<TGMessage> items = new ArrayList<>();
    Set<Integer> notified = new HashSet<>();
    boolean hasUnreadSeparator() { return false; }
    TGMessage getBottomMessage() { return getMessage(0); }
    void notifyItemInserted(int index) { notifyItemChanged(index); }
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
  static class ThreadInfo {
    void updateReadInbox(TdApi.Message message) {}
    boolean hasUnreadMessages(TdApi.Chat chat) { return false; }
  }
  static class Loader { ThreadInfo getMessageThread() { return null; } }
  static class MessagesManager {
    final MessagesAdapter adapter = new MessagesAdapter();
    final Loader loader = new Loader();
    MessagesManager() { adapter.manager = this; }
    boolean needRemoveDuplicates() { return false; }
    boolean needSort() { return false; }
    Controller controller() { return new Controller(); }
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
  static void group(TGMessage message, boolean basic) {
    message.msg.chatId = basic ? -10 : -100;
    message.chat = new TdApi.Chat();
    message.chat.id = message.msg.chatId;
    message.chat.type = basic ? new TdApi.ChatTypeBasicGroup(1) :
                               new TdApi.ChatTypeSupergroup(1, false);
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
    // Normal group sends have no senderTag until the server responds. They must
    // merge at insertion, without invoking any send-success/acknowledgment callback.
    MessagesManager live = history(100);
    TGMessage previous = live.adapter.getItem(0);
    group(previous, false);
    previous.msg.sendingState = null;
    previous.msg.senderTag = "Builder";
    TGMessage pending = new TGMessage(message(2, 101, 1, true));
    group(pending, false);
    pending.msg.senderTag = "";
    live.adapter.addMessageImpl(pending, false, true);
    check(!pending.hasHeader() && !previous.drawnTail,
      "Group bubbles must join immediately while the new message is still pending");
    check(pending.msg.sendingState instanceof TdApi.MessageSendingStatePending,
      "Do not wait for send confirmation to group bubbles");
    check(previous.msg.senderTag.equals("Builder") && pending.msg.senderTag.isEmpty(),
      "Do not fabricate or overwrite historical sender tags");
    matchesReopened(live.adapter);
    TGMessage nextPending = new TGMessage(message(3, 102, 1, true));
    group(nextPending, false);
    live.adapter.addMessageImpl(nextPending, false, true);
    check(!nextPending.hasHeader() && !pending.drawnTail && !previous.drawnTail,
      "Several unconfirmed messages join the same group immediately");
    matchesReopened(live.adapter);
    TdApi.Message confirmed = message(20, 101, 1, false);
    confirmed.chatId = -100; confirmed.senderTag = "Builder";
    live.updateMessageSendSucceeded(confirmed, 2);
    check(!previous.drawnTail && !pending.hasHeader(), "Confirmation keeps the grouping stable");
    matchesReopened(live.adapter);

    // Compare tags only where the header actually displays them. This also
    // applies to pending messages whose role metadata arrived at different times.
    for (boolean basic : new boolean[] {false, true}) {
      TGMessage a = new TGMessage(message(1, 100, 1, false));
      TGMessage b = new TGMessage(message(2, 101, 1, true));
      group(a, basic); group(b, basic);
      a.msg.senderTag = "Builder"; a.memberTag = basic ? "Builder" : null;
      check(b.mergeWith(a, true), "Own group bubbles do not display member tags");
      a.administrator = new TdApi.ChatAdministrator(1, "Moderator", false, false);
      check(b.mergeWith(a, true), "Hidden administrator labels do not separate own bubbles");
    }

    TGMessage tagged = new TGMessage(message(1, 100, 1, false));
    TGMessage changedTag = new TGMessage(message(2, 101, 1, false));
    group(tagged, false); group(changedTag, false);
    tagged.msg.senderTag = "Builder"; changedTag.msg.senderTag = "Designer";
    tagged.msg.isOutgoing = changedTag.msg.isOutgoing = false;
    check(!changedTag.mergeWith(tagged, true), "Visible incoming tags still separate groups");
    changedTag.msg.senderTag = "Builder";
    check(changedTag.mergeWith(tagged, true), "Matching incoming tags still merge");
    tagged.msg.isOutgoing = changedTag.msg.isOutgoing = true;
    tagged.bubbles = changedTag.bubbles = false;
    changedTag.msg.senderTag = "Designer";
    check(!changedTag.mergeWith(tagged, true), "Visible classic headers retain tag boundaries");
    tagged.bubbles = changedTag.bubbles = true;
    tagged.msg.viaBotUserId = changedTag.msg.viaBotUserId = 7;
    check(!changedTag.mergeWith(tagged, true), "Inline bot headers retain tag boundaries");
    tagged.msg.viaBotUserId = changedTag.msg.viaBotUserId = 0;
    tagged.msg.senderId = new TdApi.MessageSenderChat(-100);
    changedTag.msg.senderId = new TdApi.MessageSenderChat(-100);
    tagged.msg.authorSignature = "Moderator"; changedTag.msg.authorSignature = "Other admin";
    check(!changedTag.mergeWith(tagged, true), "Anonymous admin headers retain signature boundaries");
    changedTag.msg.authorSignature = "Moderator";
    check(changedTag.mergeWith(tagged, true), "Matching anonymous admin headers still merge");

    live = history(100);
    previous = live.adapter.getItem(0);
    pending = new TGMessage(message(2, 101, 1, true));
    live.adapter.addMessageImpl(pending, false, true);
    check(!pending.hasHeader() && !previous.drawnTail, "Private chats still group at insertion");
    matchesReopened(live.adapter);
    TGMessage otherSender = new TGMessage(message(3, 102, 2, true));
    live.adapter.addMessageImpl(otherSender, false, true);
    check(otherSender.hasHeader() && pending.drawnTail, "Different senders still stay separate");
    matchesReopened(live.adapter);

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
