#!/usr/bin/env python3
"""Run notification cache lifecycles using production Java methods and isolated adapters."""

import argparse
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = "app/src/main/java/org/thunderdog/challegram/telegram/"


def method(source: str, signature: str) -> str:
    start = source.index(signature)
    depth = 0
    for end in range(source.index("{", start), len(source)):
        if source[end] == "{":
            depth += 1
        elif source[end] == "}":
            depth -= 1
            if depth == 0:
                return source[start:end + 1]
    raise AssertionError(f"Unterminated method: {signature}")


ADAPTERS = r"""
import java.util.*;
@interface Nullable {}
@interface NonNull {}
@interface TdlibThread {}
class TdApi {
  abstract static class ObjectType { abstract int getConstructor(); }
  abstract static class NotificationGroupType extends ObjectType {}
  static class NotificationGroupTypeMessages extends NotificationGroupType {
    static final int CONSTRUCTOR = 1; int getConstructor() { return CONSTRUCTOR; }
  }
  static class NotificationGroupTypeMentions extends NotificationGroupType {
    static final int CONSTRUCTOR = 2; int getConstructor() { return CONSTRUCTOR; }
  }
  static class NotificationGroupTypeSecretChat extends NotificationGroupType {
    static final int CONSTRUCTOR = 3; int getConstructor() { return CONSTRUCTOR; }
  }
  static class NotificationGroupTypeCalls extends NotificationGroupType {
    static final int CONSTRUCTOR = 4; int getConstructor() { return CONSTRUCTOR; }
  }
  static class ChatTypePrivate { static final int CONSTRUCTOR = 1; }
  static class ChatTypeSecret { static final int CONSTRUCTOR = 2; }
  static class ChatTypeBasicGroup { static final int CONSTRUCTOR = 3; }
  static class ChatTypeSupergroup { static final int CONSTRUCTOR = 4; }
  static class User { boolean isContact = true; }
  static class Chat { int unreadMentionCount = 1; }
  static class Message { long id, chatId; int editDate = 0; Object senderId, content; }
  static class MessageText { String text = "A plain reply"; }
  static class TextEntity { Object type; }
  static class FormattedText { TextEntity[] entities; }
  abstract static class NotificationType extends ObjectType {}
  static class NotificationTypeNewMessage extends NotificationType {
    static final int CONSTRUCTOR = 10;
    Message message; int getConstructor() { return CONSTRUCTOR; }
  }
  static class NotificationTypeNewPushMessage extends NotificationType {
    static final int CONSTRUCTOR = 11;
    long messageId; Object senderId, content; int getConstructor() { return CONSTRUCTOR; }
  }
  static class NotificationTypeNewCall extends NotificationType {
    static final int CONSTRUCTOR = 12; int getConstructor() { return CONSTRUCTOR; }
  }
  static class NotificationTypeNewSecretChat extends NotificationType {
    static final int CONSTRUCTOR = 13; int getConstructor() { return CONSTRUCTOR; }
  }
  static class Notification { int id; NotificationType type; }
  static class NotificationGroup {
    int id, totalCount; long chatId; NotificationGroupType type; Notification[] notifications;
  }
  static class UpdateNotificationGroup {
    int notificationGroupId, totalCount; long chatId, notificationSoundId, notificationSettingsChatId;
    NotificationGroupType type; Notification[] addedNotifications; int[] removedNotificationIds;
  }
  static class UpdateNotification { int notificationGroupId; Notification notification; }
  static class UpdateMessageMentionRead {
    long chatId, messageId; int unreadMentionCount;
  }
  static class RemoveNotificationGroup { RemoveNotificationGroup(int id, int maxId) {} }
}
class Settings {
  static final int NOTIFICATION_FLAG_INCLUDE_PRIVATE = 1;
  static final int NOTIFICATION_FLAG_INCLUDE_GROUPS = 2;
  static final int NOTIFICATION_FLAG_INCLUDE_CHANNELS = 4;
  static Settings instance() { return new Settings(); }
  boolean checkNotificationFlag(int flag) { return flag == NOTIFICATION_FLAG_INCLUDE_PRIVATE; }
  boolean needSplitNotificationCategories() { return false; }
  boolean needHideSecretChats() { return false; }
}
class BitwiseUtils {
  static int splitLongToFirstInt(long value) { return (int) (value >> 32); }
  static int splitLongToSecondInt(long value) { return (int) value; }
}
class ChatId {
  static int getType(long chatId) { return TdApi.ChatTypePrivate.CONSTRUCTOR; }
  static boolean isUserChat(long chatId) { return true; }
  static boolean isSecret(long chatId) { return false; }
}
class Td {
  static boolean equalsTo(Object left, Object right) { return Objects.equals(left, right); }
  static TdApi.FormattedText textOrCaption(Object content) { return null; }
  static boolean isEmpty(TdApi.FormattedText text) { return text == null; }
}
class TD { static boolean isVisual(Object type, boolean ignored) { return false; } }
class Config { static final boolean FORCE_DISABLE_NOTIFICATIONS = false; }
class TDLib {
  static class Tag { static void notifications(String format, Object... arguments) {} }
}
class TdlibNotificationExtras { int notificationGroupId, maxNotificationId; }
class Message {
  int what; Object obj;
  static Message obtain(Object handler, int what, Object obj) {
    Message result = new Message(); result.what = what; result.obj = obj; return result;
  }
}
class TdlibUtils {
  static boolean assertChat(long chatId, TdApi.Chat chat, Object update) { return chat == null; }
}
class Listeners {
  void updateMessageMentionRead(TdApi.UpdateMessageMentionRead update, boolean counter, boolean available) {}
}
"""


SCENARIOS = r"""
public class NotificationLifecycleHarness {
  static class Scenario {
    final Tdlib tdlib = new Tdlib();
    final TdlibNotificationHelper helper = new TdlibNotificationHelper(tdlib);
    final TdlibNotificationManager manager = new TdlibNotificationManager(helper);
    Scenario() {
      tdlib.notificationManager = manager;
      tdlib.chats.put(100L, new TdApi.Chat());
      tdlib.chats.put(200L, new TdApi.Chat());
    }
    void incoming(int groupId, long chatId, boolean mention, int count, int id, long messageId) {
      TdApi.UpdateNotificationGroup update = update(groupId, chatId, mention, count);
      update.addedNotifications = new TdApi.Notification[] { notification(id, chatId, messageId, false) };
      update.notificationSoundId = 1;
      manager.incoming(update);
      manager.drain();
    }
    void read(long chatId, long messageId) {
      TdApi.UpdateMessageMentionRead update = new TdApi.UpdateMessageMentionRead();
      update.chatId = chatId; update.messageId = messageId;
      tdlib.read(update);
      manager.drain();
    }
    void dismiss(int groupId) {
      TdlibNotificationExtras extras = new TdlibNotificationExtras();
      extras.notificationGroupId = groupId;
      helper.onHide(extras);
    }
    void noReply() {
      for (TdlibNotification notification : helper.getVisibleNotifications(0)) {
        check(notification.getChatId() != 100 || notification.findMessageId() != 1000,
          "Read reply returned when another notification rebuilt the summary");
      }
    }
  }
  static void check(boolean value, String explanation) {
    if (!value) throw new AssertionError(explanation);
  }
  static TdApi.UpdateNotificationGroup update(int groupId, long chatId, boolean mention, int count) {
    TdApi.UpdateNotificationGroup update = new TdApi.UpdateNotificationGroup();
    update.notificationGroupId = groupId; update.chatId = chatId; update.totalCount = count;
    update.type = mention ? new TdApi.NotificationGroupTypeMentions()
      : new TdApi.NotificationGroupTypeMessages();
    return update;
  }
  static TdApi.Notification notification(int id, long chatId, long messageId, boolean push) {
    TdApi.Notification notification = new TdApi.Notification(); notification.id = id;
    if (push) {
      TdApi.NotificationTypeNewPushMessage type = new TdApi.NotificationTypeNewPushMessage();
      type.messageId = messageId; notification.type = type;
    } else {
      TdApi.NotificationTypeNewMessage type = new TdApi.NotificationTypeNewMessage();
      type.message = new TdApi.Message(); type.message.chatId = chatId; type.message.id = messageId;
      type.message.content = new TdApi.MessageText();
      notification.type = type;
    }
    return notification;
  }
  static void run(String name, Runnable test) {
    test.run(); System.out.println("PASS: " + name);
  }
  public static void main(String[] args) {
    run("Viewed plain reply stays absent after another chat notifies", () -> {
      Scenario s = new Scenario(); s.incoming(1, 100, true, 1, 10, 1000);
      check(s.helper.notifications.get(0).findMessage().editDate == 0, "Message must be unedited");
      s.read(100, 1000);
      boolean cleared = s.helper.notifications.isEmpty();
      s.incoming(2, 200, false, 1, 20, 2000); s.noReply();
      check(cleared, "Read acknowledgement did not clear notification cache");
      check(!s.helper.groups.containsKey(1), "Empty reply group remains cached");
      check(s.helper.hidden.contains(1), "Read reply did not clear child and summary entries");
    });
    run("Read acknowledgement removes only the viewed mention", () -> {
      Scenario s = new Scenario(); s.incoming(1, 100, true, 1, 10, 1000);
      s.incoming(1, 100, true, 2, 11, 1001); s.read(100, 1000);
      check(s.helper.groups.get(1).getTotalCount() == 1, "Reply count was not adjusted");
      check(s.helper.groups.get(1).notifications().size() == 1, "Unread reply was removed");
      check(s.helper.groups.get(1).lastNotification().findMessageId() == 1001, "Wrong reply remains");
      check(s.helper.getVisibleNotifications(0).size() == 1, "Unread reply was hidden");
      s.incoming(2, 200, false, 1, 20, 2000); s.noReply();
    });
    run("Matching IDs in other chats and message groups are preserved", () -> {
      Scenario s = new Scenario(); s.incoming(1, 100, true, 1, 10, 1000);
      s.incoming(2, 200, true, 1, 20, 1000); s.incoming(3, 100, false, 1, 30, 1000);
      s.read(100, 1000);
      check(s.helper.groups.size() == 2 && s.helper.groups.containsKey(2)
        && s.helper.groups.containsKey(3), "Unrelated notifications were removed");
    });
    run("Read retained reply cannot rejoin history on a newer reply", () -> {
      Scenario s = new Scenario(); s.incoming(1, 100, true, 1, 10, 1000);
      s.dismiss(1); s.read(100, 1000); s.incoming(1, 100, true, 1, 11, 1001); s.noReply();
      check(s.helper.notifications.size() == 1, "Read dismissed reply was retained");
    });
    run("Reading newest reply does not resurrect older dismissed history", () -> {
      Scenario s = new Scenario(); s.incoming(1, 100, true, 1, 10, 1000); s.dismiss(1);
      s.incoming(1, 100, true, 2, 11, 1001); s.read(100, 1001);
      check(s.helper.groups.get(1).isHidden(), "Dismissed history became visible after a read");
      check(s.helper.hidden.contains(1), "Dismissed history still has an Android entry");
      s.incoming(2, 200, false, 1, 20, 2000); s.noReply();
      s.incoming(1, 100, true, 2, 12, 1002);
      check(!s.helper.groups.get(1).isHidden(), "New reply must still display retained unread history");
    });
    run("Delayed group removal and repeated read updates are harmless", () -> {
      Scenario s = new Scenario(); s.incoming(1, 100, true, 1, 10, 1000);
      s.incoming(1, 100, true, 2, 11, 1001); s.read(100, 1000); s.read(100, 1000);
      TdApi.UpdateNotificationGroup update = update(1, 100, true, 1);
      update.removedNotificationIds = new int[] {10}; s.manager.incoming(update); s.manager.drain();
      check(s.helper.groups.get(1).getTotalCount() == 1, "Delayed removal changed unread count");
      s.read(100, 1001); s.manager.incoming(update(1, 100, true, 0)); s.manager.drain();
      check(s.helper.notifications.isEmpty(), "Delayed removal left stale notifications");
    });
    run("Read push notification is removed by its message ID", () -> {
      Scenario s = new Scenario(); TdApi.UpdateNotificationGroup update = update(1, 100, true, 1);
      update.addedNotifications = new TdApi.Notification[] {notification(10, 100, 1000, true)};
      update.notificationSoundId = 1; s.manager.incoming(update); s.manager.drain();
      s.read(100, 1000); s.incoming(2, 200, false, 1, 20, 2000); s.noReply();
      check(!s.helper.groups.containsKey(1), "Read push reply remains cached");
    });
    run("All cached copies of a read message are removed", () -> {
      Scenario s = new Scenario(); TdApi.UpdateNotificationGroup update = update(1, 100, true, 2);
      update.addedNotifications = new TdApi.Notification[] {
        notification(10, 100, 1000, true), notification(11, 100, 1000, false)
      };
      update.notificationSoundId = 1; s.manager.incoming(update); s.manager.drain(); s.read(100, 1000);
      check(s.helper.notifications.isEmpty() && !s.helper.groups.containsKey(1),
        "A cached copy of the read message survived");
    });
    run("Orphaned and empty groups cannot supply summary messages", () -> {
      Scenario s = new Scenario(); s.incoming(1, 100, true, 1, 10, 1000);
      s.helper.groups.remove(1);
      check(s.helper.getVisibleNotifications(0).isEmpty(), "Orphaned group reached summary");
      s = new Scenario(); s.incoming(1, 100, true, 1, 10, 1000);
      s.helper.groups.get(1).removeNotification(10);
      check(s.helper.getVisibleNotifications(0).isEmpty(), "Empty group reached summary");
    });
    run("Read events and later incoming notifications keep queue order", () -> {
      Scenario s = new Scenario(); s.incoming(1, 100, true, 1, 10, 1000);
      TdApi.UpdateMessageMentionRead read = new TdApi.UpdateMessageMentionRead();
      read.chatId = 100; read.messageId = 1000; s.tdlib.read(read);
      TdApi.UpdateNotificationGroup update = update(2, 200, false, 1);
      update.addedNotifications = new TdApi.Notification[] {notification(20, 200, 2000, false)};
      update.notificationSoundId = 1; s.manager.incoming(update); s.manager.drain(); s.noReply();
    });
    run("Dismissed notification refresh still respects the earlier fix", () -> {
      Scenario s = new Scenario(); s.incoming(1, 100, true, 1, 10, 1000); s.dismiss(1);
      TdApi.UpdateNotification update = new TdApi.UpdateNotification(); update.notificationGroupId = 1;
      update.notification = notification(10, 100, 1000, false); s.helper.editNotification(update);
      check(s.helper.groups.get(1).isHidden(), "Notification refresh resurrected dismissed reply");
    });
  }
}
"""


def harness(revision: str | None) -> str:
    def read(name: str) -> str:
        relative = JAVA_ROOT + name + ".java"
        if revision:
            return subprocess.check_output(
                ["git", "show", f"{revision}:{relative}"], cwd=ROOT, text=True
            )
        return (ROOT / relative).read_text(encoding="utf-8")

    group = read("TdlibNotificationGroup")
    helper = read("TdlibNotificationHelper")
    notification = read("TdlibNotification")
    manager = read("TdlibNotificationManager")
    tdlib = read("Tdlib")

    def methods(source: str, signatures: list[str]) -> str:
        return "\n".join(method(source, signature) for signature in signatures)

    def optional(source: str, signature: str, fallback: str) -> str:
        return method(source, signature) if signature in source else fallback

    output = ADAPTERS + "\nclass Tdlib {\n" + r"""
      final Object dataLock = new Object();
      final Map<Long, TdApi.Chat> chats = new HashMap<>();
      final Listeners listeners = new Listeners();
      TdlibNotificationManager notificationManager;
      class Storage {
        long getNotificationGroupData(int id) { return 0; }
        void setNotificationGroupData(int id, int hidden, int flags) {}
        boolean needMuteNonContacts() { return false; }
      }
      class Client { void send(Object request, Object handler) {} }
      Storage settings() { return new Storage(); }
      Client client() { return new Client(); }
      Object silentHandler() { return null; }
      boolean isUnauthorized() { return false; }
      boolean isChannel(long chatId) { return false; }
      boolean isChannelFast(long chatId) { return false; }
      TdApi.User chatUser(long chatId) { return new TdApi.User(); }
      void read(TdApi.UpdateMessageMentionRead update) { updateMessageMentionRead(update); }
    """ + method(tdlib, "private void updateMessageMentionRead (") + "\n}\n"

    output += group[group.index("public class TdlibNotificationGroup"):group.index("  public int getId ()")]
    output = output.replace("public class TdlibNotificationGroup", "class TdlibNotificationGroup")
    output = output.replace(" implements Iterable<TdlibNotification>", "")
    output += methods(group, [
        "public int getId ()", "public int getTotalCount ()", "public int maxNotificationId ()",
        "public long getChatId ()", "public boolean isMention ()", "public int getCategory ()",
        "public boolean matchesCategory (", "public List<TdlibNotification> notifications ()",
        "public TdlibNotification lastNotification ()", "public boolean isEmpty ()",
        "public TdlibNotification removeNotification (", "public int updateGroup (",
        "private int indexOfNotification (", "public TdlibNotification updateNotification (",
        "private void setNotificationData (", "private void increaseHiddenNotificationId (",
        "public void markAsHidden (", "public boolean isHidden ()", "public boolean isHidden (int",
        "public void markAsVisible ()", "public boolean needRemoveDismissedMessages ()",
    ]) + "\n"
    output += optional(group, "public int removeMessageNotifications (", "")
    output += r"""
      static final int CATEGORY_DEFAULT = 0, CATEGORY_PRIVATE = 1, CATEGORY_GROUPS = 2;
      static final int CATEGORY_CHANNELS = 3, CATEGORY_SECRET = 4;
      static final int HIDE_REASON_DEFAULT = 0, HIDE_REASON_GLOBAL = 1, HIDE_REASON_DISABLED_CHANNEL = 2;
    }
    class TdlibNotification implements Comparable<TdlibNotification> {
      final int id; TdApi.Notification notification; TdlibNotificationGroup group;
      TdlibNotification(int id) { this.id = id; }
      TdlibNotification(Tdlib tdlib, TdApi.Notification notification, TdlibNotificationGroup group) {
        this.id = notification.id; this.notification = notification; this.group = group;
      }
      void markAsEdited(boolean ignored) {}
      boolean isFromMutedForumTopic(Tdlib tdlib) { return false; }
      TdApi.NotificationType getNotificationContent() { return notification.type; }
    """ + methods(notification, [
        "public boolean isHidden ()", "public int getId ()", "public long getChatId ()",
        "public TdApi.Message findMessage ()", "public long findMessageId ()",
        "public TdlibNotificationGroup group ()", "public int compareTo (",
    ]) + "\n}\n"

    output += "class TdlibNotificationHelper {\n" + r"""
      final Tdlib tdlib;
      final Map<Integer, TdlibNotificationGroup> groups = new HashMap<>();
      final ArrayList<TdlibNotification> notifications = new ArrayList<>();
      final Set<Integer> hidden = new HashSet<>();
      final TdlibNotificationManager context = new TdlibNotificationManager(this);
      TdlibNotificationHelper(Tdlib tdlib) { this.tdlib = tdlib; }
      boolean allowNotificationPreview() { return true; }
      boolean removeReadTopicNotification(TdlibNotificationGroup group, TdlibNotification notification) {
        return false;
      }
      boolean isNotificationHiddenByFilter(TdlibNotification notification) { return false; }
      void cancelLegacyTopicNotifications(TdlibNotificationGroup group) {}
      void hideNotificationGroup(TdlibNotificationGroup group) { hidden.add(group.getId()); }
      void displayNotificationGroup(TdlibNotificationGroup group, boolean sound, long chatId) {
        getVisibleNotifications(0);
      }
    """ + methods(helper, [
        "private TdlibNotificationGroup findNotificationGroup (", "private int indexOfNotification (",
        "private static boolean accept (", "public void updateGroup (",
        "public void editNotification (", "private void onGroupChanged (",
        "public List<TdlibNotification> getVisibleNotifications (", "public void onHide (",
    ]) + "\n"
    output += optional(helper, "public void removeReadMentionNotifications (",
                       "void removeReadMentionNotifications(long chatId, long messageId) {}") + "\n}\n"

    output += "class TdlibNotificationManager {\n" + r"""
      @interface NotificationThread {}
      static final int ON_MESSAGE_MENTION_READ = 23, ON_UPDATE_NOTIFICATION_GROUP = 6;
      final TdlibNotificationHelper notification;
      class Queue extends ArrayDeque<Message> { Object getHandler() { return this; } }
      final Queue queue = new Queue();
      TdlibNotificationManager(TdlibNotificationHelper helper) { notification = helper; }
      void sendLockedMessage(Message message, Object after) { queue.add(message); }
      boolean isMessageFromActiveTopic(TdApi.Message message) { return false; }
      boolean allowNotificationSound(long chatId) { return true; }
      void incoming(TdApi.UpdateNotificationGroup update) {
        queue.add(Message.obtain(queue, ON_UPDATE_NOTIFICATION_GROUP, new Object[] {this, update}));
      }
    """
    output += optional(manager, "void onMessageMentionRead (",
                       "void onMessageMentionRead(TdApi.UpdateMessageMentionRead update) {}")
    output += optional(manager, "private void onMessageMentionReadImpl (",
                       "void onMessageMentionReadImpl(TdApi.UpdateMessageMentionRead update) {}")
    output += method(manager, "private void processNotificationGroup (")
    output += "void drain() { while (!queue.isEmpty()) { Message msg = queue.remove(); switch (msg.what) {\n"
    output += optional(manager, "case ON_MESSAGE_MENTION_READ:", "case ON_MESSAGE_MENTION_READ: { break; }")
    output += method(manager, "case ON_UPDATE_NOTIFICATION_GROUP:") + "\n} } }\n}\n"
    return output + SCENARIOS


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", help="Run against a Git revision to verify the regression")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="client-notification-test-") as directory:
        java_file = Path(directory) / "NotificationLifecycleHarness.java"
        java_file.write_text(harness(args.revision), encoding="utf-8")
        subprocess.run(["javac", "--release", "17", str(java_file)], check=True)
        subprocess.run(["java", "-cp", directory, "NotificationLifecycleHarness"], check=True)


if __name__ == "__main__":
    main()
