package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.TdApi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Small, versioned startup row cache. Never an authoritative unread snapshot. */
final class ForumTopicListCache {
  private static final int VERSION = 1;
  private static final int MAX_ROWS = 100;

  private ForumTopicListCache () { }

  static byte[] encode (List<TdApi.ForumTopic> topics) {
    List<TdApi.ForumTopic> sorted = new ArrayList<>(topics);
    ForumTopicOrder.sort(sorted);
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(VERSION);
      out.writeInt(Math.min(MAX_ROWS, sorted.size()));
      for (int i = 0; i < Math.min(MAX_ROWS, sorted.size()); i++) {
        TdApi.ForumTopic topic = sorted.get(i);
        TdApi.ForumTopicInfo info = topic.info;
        out.writeInt(info.forumTopicId);
        out.writeUTF(info.name);
        out.writeInt(info.icon != null ? info.icon.color : 0);
        out.writeLong(info.icon != null ? info.icon.customEmojiId : 0);
        out.writeInt(info.creationDate);
        out.writeBoolean(info.isGeneral);
        out.writeBoolean(info.isOutgoing);
        out.writeBoolean(info.isClosed);
        out.writeBoolean(info.isHidden);
        out.writeBoolean(info.isNameImplicit);
        out.writeLong(topic.order);
        out.writeBoolean(topic.isPinned);
        out.writeInt(topic.unreadCount);
        out.writeLong(topic.lastReadInboxMessageId);
        out.writeLong(topic.lastReadOutboxMessageId);
        out.writeInt(topic.unreadMentionCount);
        out.writeInt(topic.unreadReactionCount);
        out.writeInt(topic.unreadPollVoteCount);
        out.writeBoolean(topic.notificationSettings == null || topic.notificationSettings.useDefaultMuteFor);
        out.writeInt(topic.notificationSettings != null ? topic.notificationSettings.muteFor : 0);
      }
      return bytes.toByteArray();
    } catch (IOException e) {
      return null;
    }
  }

  static List<TdApi.ForumTopic> decode (long chatId, byte[] bytes) {
    if (bytes == null) return null;
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
      if (in.readInt() != VERSION) return null;
      int size = in.readInt();
      if (size < 0 || size > MAX_ROWS) return null;
      List<TdApi.ForumTopic> topics = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        TdApi.ForumTopic topic = new TdApi.ForumTopic();
        topic.info = new TdApi.ForumTopicInfo(chatId, in.readInt(), in.readUTF(),
          new TdApi.ForumTopicIcon(in.readInt(), in.readLong()), in.readInt(), null,
          in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean());
        topic.order = in.readLong();
        topic.isPinned = in.readBoolean();
        topic.unreadCount = in.readInt();
        topic.lastReadInboxMessageId = in.readLong();
        topic.lastReadOutboxMessageId = in.readLong();
        topic.unreadMentionCount = in.readInt();
        topic.unreadReactionCount = in.readInt();
        topic.unreadPollVoteCount = in.readInt();
        topic.notificationSettings = new TdApi.ChatNotificationSettings();
        topic.notificationSettings.useDefaultMuteFor = in.readBoolean();
        topic.notificationSettings.muteFor = in.readInt();
        topics.add(topic);
      }
      return in.available() == 0 ? topics : null;
    } catch (IOException e) {
      return null;
    }
  }
}
