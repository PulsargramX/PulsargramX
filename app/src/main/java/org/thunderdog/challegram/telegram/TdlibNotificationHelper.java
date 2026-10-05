/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 25/12/2018
 */
package org.thunderdog.challegram.telegram;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationManagerCompat;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.TDLib;
import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.data.ContentPreview;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Passcode;
import org.thunderdog.challegram.unsorted.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.vkryl.core.util.FilteredIterator;
import tgx.td.ChatId;
import tgx.td.Td;

public class TdlibNotificationHelper implements Iterable<TdlibNotificationGroup> {
  private final TdlibNotificationManager context;
  private final Tdlib tdlib;
  private final int baseNotificationId;

  private final TdlibNotificationStyleDelegate style;

  TdlibNotificationHelper (TdlibNotificationManager context, Tdlib tdlib) {
    this.context = context;
    this.tdlib = tdlib;
    this.baseNotificationId = TdlibNotificationManager.calculateBaseNotificationId(tdlib);
    this.style = new TdlibNotificationStyle(this, tdlib);
    cancelLegacyTopicNotifications();
  }

  public TdlibNotificationManager context () {
    return context;
  }

  // API

  private final ArrayList<TdlibNotification> notifications = new ArrayList<>();
  private final Map<Integer, TdlibNotificationGroup> groups = new HashMap<>();

  @Nullable
  private TdlibNotificationGroup findNotificationGroup (int groupId) {
    return groups.get(groupId);
  }

  private int indexOfNotification (int notificationId) {
    int i = 0;
    for (TdlibNotification notification : notifications) {
      if (notification.getId() == notificationId)
        return i;
      i++;
    }
    return -1;
  }

  private static boolean accept (TdApi.NotificationGroupType type) {
    if (Config.FORCE_DISABLE_NOTIFICATIONS) {
      // Ignore all notifications in experimental builds
      return false;
    }
    switch (type.getConstructor()) {
      case TdApi.NotificationGroupTypeMessages.CONSTRUCTOR:
      case TdApi.NotificationGroupTypeMentions.CONSTRUCTOR:
      case TdApi.NotificationGroupTypeSecretChat.CONSTRUCTOR: {
        return true;
      }
      case TdApi.NotificationGroupTypeCalls.CONSTRUCTOR:
        break;
    }
    return false;
  }

  /**
   * Check if a notification should be hidden by message filter.
   * 
   * @param notification The notification to check
   * @return true if the notification's message matches a HIDE filter
   */
  private boolean isNotificationHiddenByFilter (@NonNull TdlibNotification notification) {
    // Get the message from the notification
    TdApi.Message message = notification.findMessage();
    if (message == null) {
      return false;
    }

    // Check if the message would be hidden by filter
    return ContentPreview.isMessageHiddenByFilter(message);
  }

  public void onTdlibRestart () { // This update means soon updateActiveNotifications will arrive
    // notifications.clear();
    // groups.clear();
  }

  private void hideUnknownNotification (NotificationManagerCompat manager, String tag, int notificationId, boolean isSummary, TdlibNotificationExtras extras) {
    manager.cancel(
      tag,
      notificationId
    );
  }

  @AnyThread
  public boolean isUnknownGroup (int groupId) {
    try {
      return !groups.containsKey(groupId);
    } catch (Throwable ignored) { }
    return true;
  }

  public void removeNotificationGroup (TdlibNotificationExtras extras) {
    TdlibNotificationGroup group = findNotificationGroup(extras.notificationGroupId);
    if (group != null && !group.isEmpty()) {
      tdlib.client().send(new TdApi.RemoveNotificationGroup(extras.notificationGroupId, extras.maxNotificationId), tdlib.silentHandler());
    } else {
      String notificationTag = TdlibNotificationManager.getMessageNotificationTag(extras.accountId, extras.category);
      int notificationId = getNotificationIdForGroup(extras.notificationGroupId);
      NotificationManagerCompat manager = manager();
      hideUnknownNotification(manager, notificationTag, notificationId, false, extras);
      if (!hasVisibleNotifications(extras.category)) {
        int baseNotificationId = getBaseNotificationId(extras.category);
        hideUnknownNotification(manager, notificationTag, baseNotificationId, true, extras);
      }
    }
  }

  public void removeReadMentionNotifications (long chatId, long messageId) {
    // Replies and mentions have their own read acknowledgements. Drop the cached
    // notification immediately so a later alert cannot reuse it in the summary
    // while TDLib's notification-group removal is still pending.
    Iterator<TdlibNotificationGroup> iterator = groups.values().iterator();
    while (iterator.hasNext()) {
      TdlibNotificationGroup group = iterator.next();
      if (group.getChatId() != chatId || !group.isMention()) continue;
      List<TdlibNotification> removedNotifications = new ArrayList<>();
      if (group.removeMessageNotifications(messageId, removedNotifications) == 0) continue;
      notifications.removeAll(removedNotifications);
      if (group.isEmpty()) {
        iterator.remove();
      }
      if (group.isEmpty() || group.isHidden()) {
        hideNotificationGroup(group);
      } else {
        displayNotificationGroup(group, false, 0);
      }
    }
  }

  public void removeTopicNotifications (long chatId, TdApi.MessageTopic topicId, long lastReadInboxMessageId) {
    for (TdlibNotificationGroup group : groups.values()) {
      if (group.getChatId() != chatId) {
        continue;
      }
      if (group.isMention()) continue;
      for (TdlibNotification notification : group.notifications()) {
        TdApi.Message message = notification.findMessage();
        if (message != null && !message.isOutgoing && message.id <= lastReadInboxMessageId &&
            Td.equalsTo(message.topicId, topicId)) {
          tdlib.client().send(
            new TdApi.RemoveNotification(group.getId(), notification.getId()),
            tdlib.silentHandler()
          );
        }
      }
    }
  }

  private boolean removeReadTopicNotification (TdlibNotificationGroup group, TdlibNotification notification) {
    if (group.isMention() || !tdlib.isReadPrivateTopicMessage(notification.findMessage())) return false;
    tdlib.client().send(new TdApi.RemoveNotification(group.getId(), notification.getId()), tdlib.silentHandler());
    return true;
  }

  private void cancelLegacyTopicNotifications () {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return;
    }
    try {
      NotificationManager manager = (NotificationManager) UI.getAppContext().getSystemService(Context.NOTIFICATION_SERVICE);
      if (manager == null) {
        return;
      }
      String accountGroupPrefix = "messages" + tdlib.id() + "_";
      for (StatusBarNotification status : manager.getActiveNotifications()) {
        String groupKey = status.getNotification().getGroup();
        if (groupKey != null && groupKey.startsWith(accountGroupPrefix) && groupKey.contains("_forum_")) {
          manager.cancel(status.getId());
        }
      }
    } catch (Throwable ignored) { }
  }

  public void cancelLegacyTopicNotifications (@NonNull TdlibNotificationGroup group) {
    NotificationManagerCompat manager = manager();
    for (long topicId : group.getAllForumTopicIds()) {
      manager.cancel(getNotificationIdForTopicView(group.getId(), topicId));
    }
  }

  public void abortCancelableOperations () {
    Context context = UI.getAppContext();
    style.cancelPendingMediaPreviewDownloads(context, this);
  }

  public void restoreState (TdApi.UpdateActiveNotifications update) {
    this.notifications.clear();
    this.groups.clear();

    boolean needRebuild = false;
    boolean removedReadNotifications = false;
    List<TdlibNotificationGroup> readGroups = new ArrayList<>();
    for (TdApi.NotificationGroup rawGroup : update.groups) {
      if (accept(rawGroup.type)) {
        TdlibNotificationGroup group = new TdlibNotificationGroup(tdlib, rawGroup);
        for (TdlibNotification notification : new ArrayList<>(group.notifications())) {
          if (removeReadTopicNotification(group, notification)) {
            group.removeNotification(notification.getId());
            removedReadNotifications = true;
          }
        }
        if (!group.isEmpty()) {
          groups.put(rawGroup.id, group);
          notifications.addAll(group.notifications());
          needRebuild = needRebuild || (!group.isEmpty() && !group.isHidden());
        } else {
          readGroups.add(group);
        }
      }
    }
    if (!notifications.isEmpty()) {
      Collections.sort(notifications);
      tdlib.context().setHavePendingNotifications(tdlib.id(), true);
    }
    for (TdlibNotificationGroup group : readGroups) hideNotificationGroup(group);
    if (needRebuild) {
      if (removedReadNotifications || !haveRebuilt || Settings.instance().needNotificationAppVersionUpdate(tdlib.id())) {
        haveRebuilt = true;
        rebuild();
      }
    }
  }

  private boolean haveRebuilt;

  public void updateGroup (TdApi.UpdateNotificationGroup update) {
    if (!accept(update.type))
      return;
    boolean isSilent = update.notificationSoundId == 0;
    if (!isSilent && update.notificationSettingsChatId != 0 && ChatId.isUserChat(update.notificationSettingsChatId) && tdlib.settings().needMuteNonContacts()) {
      TdApi.User user = tdlib.chatUser(update.notificationSettingsChatId);
      if (user != null && !user.isContact) {
        TDLib.Tag.notifications("Making notification from chatId=%d silent, because of user preferences for %d", update.chatId, update.notificationSettingsChatId);
        isSilent = true;
      }
    }
    TdlibNotificationGroup group = findNotificationGroup(update.notificationGroupId);
    if (group != null) {
      cancelLegacyTopicNotifications(group);
      List<TdlibNotification> addedNotifications, removedNotifications;
      if (update.addedNotifications != null && update.addedNotifications.length > 0) {
        addedNotifications = new ArrayList<>(update.addedNotifications.length);
      } else {
        addedNotifications = null;
      }
      if (update.removedNotificationIds != null && update.removedNotificationIds.length > 0) {
        removedNotifications = new ArrayList<>(update.removedNotificationIds.length);
      } else {
        removedNotifications = null;
      }

      int oldTotalCount = group.getTotalCount();

      int visualChangeCount = group.updateGroup(update, addedNotifications, removedNotifications);

      // Filter out notifications from muted forum topics and make silent if all are muted
      if (addedNotifications != null && !addedNotifications.isEmpty()) {
        Iterator<TdlibNotification> iter = addedNotifications.iterator();
        while (iter.hasNext()) {
          TdlibNotification notification = iter.next();
          if (removeReadTopicNotification(group, notification)) {
            iter.remove();
            visualChangeCount--;
            group.removeNotification(notification.getId());
          } else if (notification.isFromMutedForumTopic(tdlib)) {
            TDLib.Tag.notifications("Filtering notification from muted forum topic, chatId=%d", update.chatId);
            iter.remove();
            visualChangeCount--;
            // Also remove from the group's internal list
            group.removeNotification(notification.getId());
          } else if (context.isMessageFromActiveTopic(notification.findMessage())) {
            TDLib.Tag.notifications("Filtering notification from active topic, chatId=%d", update.chatId);
            iter.remove();
            visualChangeCount--;
            group.removeNotification(notification.getId());
          } else if (isNotificationHiddenByFilter(notification)) {
            TDLib.Tag.notifications("Filtering notification hidden by message filter, chatId=%d", update.chatId);
            iter.remove();
            visualChangeCount--;
            // Also remove from the group's internal list
            group.removeNotification(notification.getId());
          }
        }
        if (addedNotifications.isEmpty()) {
          isSilent = true;
        }
      }

      if (removedNotifications != null && !removedNotifications.isEmpty()) {
        notifications.removeAll(removedNotifications);
      }

      if (addedNotifications != null && !addedNotifications.isEmpty()) {
        notifications.addAll(addedNotifications);
        Collections.sort(notifications);
      }

      if (group.isEmpty() || tdlib.isUnauthorized()) {
        groups.remove(group.getId());
        // Log.i("Finally hiding notification group %d", group.getId());
        hideNotificationGroup(group);
        return;
      }

      if (visualChangeCount == 0) // Nothing visually changed
        return;

      if (oldTotalCount == update.totalCount && isSilent) {
        boolean hasVisualChange = false;
        if (addedNotifications != null) {
          for (TdlibNotification notification : addedNotifications) {
            if (!notification.isHidden()) {
              hasVisualChange = true;
            }
          }
        }
        if (!hasVisualChange && removedNotifications != null) {
          for (TdlibNotification notification : removedNotifications) {
            if (!notification.isHidden()) {
              hasVisualChange = true;
            }
          }
        }
        if (!hasVisualChange) {
          // nothing changed visually, ignoring update
          return;
        }
      }
      if (!allowNotificationPreview() && isSilent) {
        // nothing changed visually, ignoring update
        return;
      }
    } else {
      if (update.removedNotificationIds != null && update.removedNotificationIds.length > 0) {
        for (int notificationId : update.removedNotificationIds) {
          int i = indexOfNotification(notificationId);
          if (i != -1) {
            notifications.remove(i);
          }
        }
      }
      if (update.addedNotifications == null || update.addedNotifications.length == 0 || tdlib.isUnauthorized())
        return;
      group = new TdlibNotificationGroup(tdlib, update);
      if (group.isEmpty())
        return;

      // Filter out notifications from muted forum topics and hidden by message filter for new groups
      List<TdlibNotification> groupNotifications = new ArrayList<>(group.notifications());
      Iterator<TdlibNotification> iter = groupNotifications.iterator();
      while (iter.hasNext()) {
        TdlibNotification notification = iter.next();
        if (removeReadTopicNotification(group, notification)) {
          iter.remove();
          group.removeNotification(notification.getId());
        } else if (notification.isFromMutedForumTopic(tdlib)) {
          TDLib.Tag.notifications("Filtering notification from muted forum topic (new group), chatId=%d", update.chatId);
          iter.remove();
          // Also remove from the group's internal list
          group.removeNotification(notification.getId());
        } else if (context.isMessageFromActiveTopic(notification.findMessage())) {
          TDLib.Tag.notifications("Filtering notification from active topic (new group), chatId=%d", update.chatId);
          iter.remove();
          group.removeNotification(notification.getId());
        } else if (isNotificationHiddenByFilter(notification)) {
          TDLib.Tag.notifications("Filtering notification hidden by message filter (new group), chatId=%d", update.chatId);
          iter.remove();
          // Also remove from the group's internal list
          group.removeNotification(notification.getId());
        }
      }
      if (groupNotifications.isEmpty()) {
        isSilent = true;
      }

      // If group is now empty after filtering, don't add it
      if (group.isEmpty()) {
        TDLib.Tag.notifications("Group is empty after filtering, not adding, chatId=%d", update.chatId);
        return;
      }

      groups.put(update.notificationGroupId, group);
      notifications.addAll(groupNotifications);
      Collections.sort(notifications);
    }
    boolean needNotification = !isSilent && context.allowNotificationSound(update.chatId);
    onGroupChanged(group, needNotification, update.notificationSettingsChatId, null);
  }

  public void editNotification (TdApi.UpdateNotification update) {
    TdlibNotificationGroup group = findNotificationGroup(update.notificationGroupId);
    if (group != null) {
      TdlibNotification editedNotification = group.updateNotification(update.notification);
      if (editedNotification != null) {
        int i = indexOfNotification(update.notification.id);
        if (i == -1)
          throw new IllegalStateException("Notification not found in the global list");
        notifications.set(i, editedNotification);
        if (removeReadTopicNotification(group, editedNotification)) return;
        onGroupChanged(group, false, 0, editedNotification);
      }
    }
  }

  private void onGroupChanged (TdlibNotificationGroup group, boolean needNotification, long notificationSettingsChatId, @Nullable TdlibNotification edited) {
    if (!needNotification && group.isHidden()) {
      if (edited != null) {
        edited.markAsEdited(true);
      }
      // Retaining dismissed messages for the next notification must not let
      // edits to those messages make the dismissed group visible again.
      return;
    } else if (edited != null) {
      edited.markAsEdited(false);
    }
    group.markAsVisible();
    displayNotificationGroup(group, needNotification, notificationSettingsChatId);
  }

  public void onNotificationChannelGroupReset (long accountUserId) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (tdlib.notifications().resetChannelCache(accountUserId)) {
        rebuild();
      }
    }
  }

  // Getters

  public NotificationManagerCompat manager () {
    return NotificationManagerCompat.from(UI.getContext());
  }

  public Tdlib tdlib () {
    return tdlib;
  }

  public String tag (int category) {
    return TdlibNotificationManager.getMessageNotificationTag(tdlib.accountId(), category);
  }

  public int getBaseNotificationId (int category) {
    return baseNotificationId + category;
  }

  public int getNotificationIdForGroup (int groupId) {
    int offset = TdlibNotificationGroup.MAX_CATEGORY + 1 + groupId;
    int available = Integer.MAX_VALUE - baseNotificationId;
    if (offset >= available) {
      return 1 + offset;
    } else {
      return baseNotificationId + offset;
    }
  }

  /**
   * Generate a unique notification ID for a specific forum topic within a group.
   * Uses a hash-based approach to avoid ID collisions.
   */
  public int getNotificationIdForTopicView (int groupId, long topicId) {
    // Combine groupId with topicId hash to generate unique ID
    // Use a large offset to avoid collisions with regular group IDs
    int topicHash = Long.hashCode(topicId) & 0x7FFFFFFF; // Ensure positive
    int topicOffset = (topicHash % 100000) + 100000; // Range: 100000-199999
    return baseNotificationId + (TdlibNotificationGroup.MAX_CATEGORY + 1) + groupId * 200000 + topicOffset;
  }

  public boolean isEmpty () {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      long accountUserId = tdlib.myUserId(true);
      if (accountUserId != 0) {
        try {
          TdlibNotificationChannelGroup channelGroup = tdlib.notifications().getChannelCache();
          for (TdlibNotificationGroup group : this) {
            if (channelGroup.getChannel(group, false) != null)
              return false;
          }
          return true;
        } catch (TdlibNotificationChannelGroup.ChannelCreationFailureException e) {
          TDLib.Tag.notifications("Unable to create some notification channels for userId %d:\n%s",
            accountUserId,
            Log.toString(e)
          );
          tdlib.settings().trackNotificationChannelProblem(e, 0);
        }
      }
    }
    return !iterator().hasNext();
  }

  // Grouped notification

  @Nullable
  public TdlibNotificationGroup findGroup (int groupId) {
    TdlibNotificationGroup group = groups.get(groupId);
    return group != null && !group.isEmpty() && !group.isHidden() ? group : null;
  }

  @RequiresApi(Build.VERSION_CODES.O)
  public String findCommonChannelId (int category) throws TdlibNotificationChannelGroup.ChannelCreationFailureException {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      TdlibNotificationChannelGroup channelGroup = tdlib.notifications().getChannelCache();
      android.app.NotificationChannel channel = null;
      List<TdlibNotification> notifications = lastNotifications(category, true);
      if (notifications != null && !notifications.isEmpty()) {
        for (int i = notifications.size() - 1; i >= 0; i--) {
          channel = (android.app.NotificationChannel) channelGroup.getChannel(notifications.get(i).group(), false);
          if (channel != null)
            break;
        }
      }
      if (channel == null) {
        notifications = lastNotifications(category, false);
        if (notifications != null && !notifications.isEmpty()) {
          for (int i = notifications.size() - 1; i >= 0; i--) {
            channel = (android.app.NotificationChannel) channelGroup.getChannel(notifications.get(i).group(), false);
            if (channel != null)
              break;
          }
          if (channel == null) {
            channel = (android.app.NotificationChannel) channelGroup.getChannel(notifications.get(notifications.size() - 1).group(), true);
          }
        }
      }
      return channel != null ? channel.getId() : null;
    }
    return null;
  }

  public List<TdlibNotification> lastNotifications (int category, boolean onlyVisible) {
    ArrayList<TdlibNotification> notifications = null;
    if (onlyVisible) {
      for (TdlibNotificationGroup group : this) {
        if (group.matchesCategory(category)) {
          if (notifications == null)
            notifications = new ArrayList<>(groups.size());
          notifications.add(group.lastNotification());
        }
      }
    } else if (!groups.isEmpty()) {
      notifications = new ArrayList<>(groups.size());
      for (TdlibNotificationGroup group : groups.values()) {
        if (group.matchesCategory(category)) {
          notifications.add(group.lastNotification());
        }
      }
    }
    if (notifications != null) {
      Collections.sort(notifications);
      notifications.trimToSize();
    }
    return notifications;
  }

  public boolean allowNotificationPreview () {
    return !Passcode.instance().isLocked() || Passcode.instance().displayNotifications();
  }

  public int getTotalCount (int category) {
    int totalCount = 0;
    for (TdlibNotificationGroup group : groups.values()) {
      if (group.matchesCategory(category))
        totalCount += group.getTotalCount();
    }
    return totalCount;
  }

  public boolean hasVisibleNotifications (int category) {
    for (TdlibNotificationGroup group : this) {
      if (group.matchesCategory(category))
        return true;
    }
    return false;
  }

  public List<TdlibNotification> getVisibleNotifications (int category) {
    List<TdlibNotification> notifications = new ArrayList<>(this.notifications.size());
    for (TdlibNotification notification : this.notifications) {
      TdlibNotificationGroup group = notification.group();
      if (groups.get(group.getId()) == group && !group.isEmpty() &&
          group.matchesCategory(category) && !group.isHidden() && !notification.isHidden()) {
        notifications.add(notification);
      }
    }
    return notifications;
  }

  public int calculateMessageCount (int category) {
    int totalCount = 0;
    for (TdlibNotificationGroup group : groups.values()) {
      if (group.matchesCategory(category))
        totalCount += group.visualSize();
    }
    return totalCount;
  }

  public int calculateChatsCount (int category) {
    Set<Long> chatIds = new HashSet<>(groups.size());
    for (TdlibNotificationGroup group : groups.values()) {
      if (group.matchesCategory(category))
        chatIds.add(group.getChatId());
    }
    return chatIds.size();
  }

  @NonNull
  @Override
  public Iterator<TdlibNotificationGroup> iterator () {
    return new FilteredIterator<>(groups.values(), item -> !item.isEmpty() && !item.isHidden());
  }

  public void onHideAll (int category) {
    List<TdlibNotificationGroup> hiddenGroups = new ArrayList<>();
    for (TdlibNotificationGroup group : this) {
      if (group.matchesCategory(category)) {
        group.markAsHidden(TdlibNotificationGroup.HIDE_REASON_GLOBAL);
        hiddenGroups.add(group);
      }
    }
    for (TdlibNotificationGroup group : hiddenGroups) hideNotificationGroup(group);
  }

  public void onHide (TdlibNotificationExtras extras) {
    TdlibNotificationGroup group = groups.get(extras.notificationGroupId);
    if (group != null) {
      group.markAsHidden(TdlibNotificationGroup.HIDE_REASON_DEFAULT);
      // Android can display both a child and a summary for this group.
      // Dismissing either must also clear the other entry.
      hideNotificationGroup(group);
    } else {
      tdlib.settings().setNotificationGroupData(extras.notificationGroupId, extras.maxNotificationId, 0);
    }
  }

  public void onDropNotificationData (boolean hideAll) {
    boolean update = false;
    for (TdlibNotificationGroup group : groups.values()) {
      if (group.dropNotificationData()) {
        update = true;
      }
    }
    if (hideAll) {
      for (TdlibNotificationGroup group : this) {
        group.markAsHidden(TdlibNotificationGroup.HIDE_REASON_DATA_DROP);
        hideNotificationGroup(group);
      }
      groups.clear();
      notifications.clear();
    }
    tdlib.settings().deleteHiddenNotificationIds();
    if (!hideAll && update) {
      rebuild();
    }
  }

  public long findSingleChatId () {
    if (!groups.isEmpty()) {
      long singleChatId = 0;
      for (TdlibNotificationGroup group : groups.values()) {
        long chatId = group.getChatId();
        if (singleChatId == 0)
          singleChatId = chatId;
        else if (singleChatId != chatId)
          return 0;
      }
      return singleChatId;
    }
    return 0;
  }

  public long findSingleMessageId (long chatId) {
    TdlibNotificationGroup oldestGroup = null;
    for (TdlibNotificationGroup group : groups.values()) {
      if (group.getChatId() == chatId) {
        if (oldestGroup == null || oldestGroup.firstNotificationId() > group.firstNotificationId()) {
          oldestGroup = group;
        }
      }
    }
    return oldestGroup != null ? oldestGroup.findTargetMessageId() : 0;
  }

  public boolean needPreview (@NonNull TdlibNotificationGroup group) {
    return tdlib.notifications().isShowPreviewEnabled(group.getChatId(), group.isMention());
  }

  public boolean needContentPreview (@NonNull TdlibNotificationGroup group) {
    return tdlib.notifications().needContentPreview(group.getChatId(), group.isMention());
  }

  public boolean needContentPreview (@NonNull TdlibNotificationGroup group, @Nullable TdlibNotification notification) {
    return notification != null && needContentPreview(group) && notification.needContentPreview();
  }

  // Impl

  private void displayNotificationGroup (@NonNull TdlibNotificationGroup group, boolean needNotification, long notificationSettingsChatId) {
    Context context = UI.getAppContext();
    int badgeCount = tdlib.getUnreadBadgeCount();
    boolean allowPreview = allowNotificationPreview();
    TdlibNotificationSettings settings = needNotification && !group.isHidden() ? new TdlibNotificationSettings(tdlib, notificationSettingsChatId, group) : null;
    style.displayNotificationGroup(context, this, badgeCount, allowPreview, group, settings);
    tdlib.context().setHavePendingNotifications(tdlib.id(), true);
  }

  private void hideNotificationGroup (@NonNull TdlibNotificationGroup group) {
    cancelLegacyTopicNotifications(group);
    Context context = UI.getAppContext();
    int badgeCount = tdlib.getUnreadBadgeCount();
    boolean allowPreview = allowNotificationPreview();
    style.hideNotificationGroup(context, this, badgeCount, allowPreview, group);
    tdlib.context().setHavePendingNotifications(tdlib.id(), !isEmpty());
  }

  private void rebuild (@Nullable TdApi.NotificationSettingsScope scope, long specificChatId, int specificGroupId) {
    final boolean haveNotifications = !isEmpty();
    if (haveNotifications) {
      Context context = UI.getAppContext();
      int badgeCount = tdlib.getUnreadBadgeCount();
      boolean allowPreview = allowNotificationPreview();
      style.rebuildNotificationsSilently(context, this, badgeCount, allowPreview, scope, specificChatId, specificGroupId);
    }
    tdlib.context().setHavePendingNotifications(tdlib.id(), haveNotifications);
  }

  public void rebuild () {
    rebuild(null, 0, 0);
  }

  public void rebuild (TdApi.NotificationSettingsScope scope) {
    rebuild(scope, 0, 0);
  }

  public void rebuildChat (long specificChatId) {
    rebuild(null, specificChatId, 0);
  }

  public void rebuildGroup (int groupId) {
    rebuild(null, 0, groupId);
  }

  public void onChatOpened (long chatId) {
    if (ChatId.isSecret(chatId)) {
      for (TdlibNotificationGroup group : groups.values()) {
        if (group.getChatId() == chatId) {
          for (TdlibNotification notification : group.notifications()) {
            if (notification.isNewSecretChat()) {
              tdlib.client().send(new TdApi.RemoveNotification(group.getId(), notification.getId()), tdlib.silentHandler());
            }
          }
          break;
        }
      }
    }
  }
}
