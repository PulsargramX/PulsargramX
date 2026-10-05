#!/usr/bin/env python3
"""Source-level regression checks for private bot topics (no Android/Gradle build required)."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def java_method(source: str, signature: str) -> str:
    start = source.index(signature)
    body_start = source.index("{", start)
    depth = 0
    for index in range(body_start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start : index + 1]
    raise AssertionError(f"Unterminated Java method: {signature}")


class PrivateChatTopicsSourceTest(unittest.TestCase):
    def test_forum_preload_finishes_without_navigation_or_echo_loops(self) -> None:
        tdlib = read("app/src/main/java/org/thunderdog/challegram/telegram/Tdlib.java")
        new_chat = java_method(tdlib, "private void updateNewChat (")
        update = java_method(tdlib, "private void updateForumTopic (TdApi.UpdateForumTopic")
        page = java_method(tdlib, "private void fetchForumTopicsPage (")
        cached = java_method(tdlib, "public @Nullable List<TdApi.ForumTopic> getCachedForumTopics (")
        self.assertIn("forumUnreadTopicCount(update.chat.id)", new_chat)
        self.assertIn("!ForumTopicState.sameUpdate(previousUpdate, update)", update)
        self.assertIn("ForumTopicState.matchesUpdate(topic", page)
        self.assertIn("request.changes.acknowledge(topic.info.forumTopicId", page)
        self.assertIn("updateForumTopicsCache(chatId, Arrays.asList(page.topics), false)", page)
        self.assertIn("settings().getForumTopicList(chatId)", cached)
        self.assertNotIn("completeForumTopics.add", cached)

        controller = read("app/src/main/java/org/thunderdog/challegram/ui/ForumTopicsController.java")
        load = java_method(controller, "private void loadTopics ()")
        self.assertIn("loadForumTopicsFirstPage", load)
        self.assertNotIn("new TdApi.GetForumTopics", load)
        self.assertNotIn("new TdApi.GetForumTopics", controller)
        self.assertNotIn("updateForumTopicsCache", controller)
        self.assertIn("onForumTopicsChanged", controller)
        changes = read("app/src/main/java/org/thunderdog/challegram/telegram/ForumTopicChanges.java")
        self.assertIn("!activityTopicIds.contains(topicId)", changes)

    def test_topic_rebinding_preserves_icon_playback(self) -> None:
        view = read("app/src/main/java/org/thunderdog/challegram/ui/ForumTopicView.java")
        bind = java_method(view, "public void setTopic (Tdlib tdlib, TdApi.ForumTopic topic, @Nullable String highlightQuery)")
        self.assertNotIn("iconReceiver.clear()", bind)
        self.assertNotIn("this.gifFile = null", bind)
        self.assertIn("if (iconChanged)", bind)
        self.assertIn("customEmojiId != nextCustomEmojiId", bind)
        self.assertIn("getIconSize(listMode) != getIconSize(newListMode)", bind)

        callback = java_method(view, "public void onCustomEmojiLoaded (")
        self.assertLess(callback.index("UI.post("), callback.index("customEmoji = entry"))
        self.assertIn("tdlib.emoji() != context", callback)

    def test_topic_drafts_remain_visible_with_unread_messages(self) -> None:
        view = read("app/src/main/java/org/thunderdog/challegram/ui/ForumTopicView.java")
        bind = java_method(view, "public void setTopic (Tdlib tdlib, TdApi.ForumTopic topic, @Nullable String highlightQuery)")
        draft_condition = bind.split("boolean hasDraft =", 1)[1].split(";", 1)[0]
        self.assertIn("DraftMessageContentText.CONSTRUCTOR", draft_condition)
        self.assertNotIn("unreadCount", draft_condition)
        self.assertLess(bind.index("if (hasDraft)"), bind.index("else if (topic.lastMessage != null)"))

    def test_checked_in_tdlib_binding_exposes_bot_topic_capability(self) -> None:
        td_api = read("tdlib/src/main/java/org/drinkless/tdlib/TdApi.java")
        self.assertIn("public boolean hasTopics;", td_api)
        self.assertIn("public boolean allowsUsersToCreateTopics;", td_api)
        self.assertIn("@Nullable public MessageTopic topicId;", td_api)
        self.assertIn("class MessageTopicForum extends MessageTopic", td_api)

    def test_detection_is_capability_based_and_private_only(self) -> None:
        support = read(
            "app/src/main/java/org/thunderdog/challegram/telegram/ChatTopicSupport.java"
        )
        self.assertIn("TdApi.ChatTypePrivate.CONSTRUCTOR", support)
        self.assertIn("TdApi.UserTypeBot.CONSTRUCTOR", support)
        self.assertIn("((TdApi.UserTypeBot) userType).hasTopics", support)

        routing = read(
            "app/src/main/java/org/thunderdog/challegram/telegram/MessageTopicRouting.java"
        )
        self.assertIn("boolean hasTopics", routing)
        self.assertNotIn("boolean isBotChat", routing)

        tdlib = read("app/src/main/java/org/thunderdog/challegram/telegram/Tdlib.java")
        private_topics = java_method(tdlib, "public boolean isPrivateChatWithTopics (@Nullable TdApi.Chat chat)")
        self.assertIn("isRepliesChat(chat.id)", private_topics)

    def test_replies_routing_precedes_topic_chat_routing(self) -> None:
        routing = read(
            "app/src/main/java/org/thunderdog/challegram/telegram/MessageTopicRouting.java"
        )
        route_method = java_method(routing, "fromExternalMessageThreadId (")
        reject = route_method.index("if (messageThreadId <= 0)")
        replies = route_method.index("if (isRepliesChat)")
        topics = route_method.index("if (hasTopics)")
        generic = route_method.index("if (hasMessageThreads)")
        self.assertLess(reject, replies)
        self.assertLess(replies, topics)
        self.assertLess(topics, generic)
        self.assertIn("new TdApi.MessageTopicThread(messageThreadId)", route_method)

    def test_private_bot_topic_capability_only_enables_create_and_delete(self) -> None:
        controller = read(
            "app/src/main/java/org/thunderdog/challegram/ui/ForumTopicsController.java"
        )
        create = java_method(controller, "private boolean canCreateTopics ()")
        delete = java_method(controller, "private boolean canDeleteTopics ()")
        manage = java_method(controller, "private boolean canManageTopics ()")
        options = java_method(controller, "private void showTopicOptions (")

        self.assertIn("canCreateOrDeletePrivateTopics()", create)
        self.assertIn("canCreateOrDeletePrivateTopics()", delete)
        self.assertIn("canManageTopics()", delete)
        self.assertNotIn("allowsUsersToCreateTopics", manage)
        self.assertIn("return false;", manage)
        self.assertIn("boolean canDelete = canDeleteTopics();", options)
        self.assertIn("if (canDelete && !topic.info.isGeneral)", options)

    def test_private_topic_chat_opens_topic_list_without_view_as_topics(self) -> None:
        ui = read("app/src/main/java/org/thunderdog/challegram/telegram/TdlibUi.java")
        self.assertIn("boolean privateChatWithTopics = tdlib.isPrivateChatWithTopics(chat);", ui)
        self.assertIn("(privateChatWithTopics || chat.viewAsTopics)", ui)
        self.assertIn("new ForumTopicsController", ui)

    def test_topic_scope_is_preserved_for_history_intents_and_sends(self) -> None:
        loader = read(
            "app/src/main/java/org/thunderdog/challegram/component/chat/MessagesLoader.java"
        )
        self.assertIn(
            "return messageThread != null ? messageThread.getMessageTopicId() : topicId;",
            loader,
        )
        self.assertIn("new TdApi.GetForumTopicHistory", loader)

        intents = read("app/src/main/java/org/thunderdog/challegram/tool/Intents.java")
        self.assertIn('Td.put(intent, "message_topic", messageTopicId);', intents)

        share = read("app/src/main/java/org/thunderdog/challegram/ui/ShareController.java")
        self.assertIn("hasRequiredPrivateTopicSelections()", share)
        self.assertIn("new TdApi.MessageTopicForum(topicId.intValue())", share)
        self.assertIn("generateFunctionsForChat(chatId, chat, messageTopicId, sendOptions, functions)", share)
        self.assertIn("new TdlibUi.ChatOpenParameters().messageTopic(selectedTopic)", share)

        main = read("app/src/main/java/org/thunderdog/challegram/ui/MainController.java")
        self.assertIn("TdApi.MessageTopic topicId", main)
        self.assertIn("TD.processAlbum(tdlib, chatId, topicId, sendOptions", main)
        self.assertIn("TD.processSingle(tdlib, chatId, topicId, sendOptions", main)

        messages = read("app/src/main/java/org/thunderdog/challegram/ui/MessagesController.java")
        self.assertIn("sendBotStartMessage(start.getUserId(), chat.id, getMessageTopicId()", messages)
        self.assertIn("sendBotStartMessage(tdlib.chatUserId(chatId), chat.id, getMessageTopicId()", messages)

        tdlib = read("app/src/main/java/org/thunderdog/challegram/telegram/Tdlib.java")
        self.assertIn("sendBotStartMessage (long botUserId, long chatId, @Nullable TdApi.MessageTopic topicId", tdlib)

    def test_topic_draft_restores_and_clears_in_its_own_composer_scope(self) -> None:
        messages = read(
            "app/src/main/java/org/thunderdog/challegram/ui/MessagesController.java"
        )
        get_draft = java_method(messages, "public TdApi.DraftMessage getDraftMessage ()")
        save_draft = java_method(messages, "private void saveDraft ()")

        # Replies keep their ThreadInfo draft; forum topics use the ForumTopic draft
        # without leaking the chat draft; all non-forum chat modes keep their prior fallback.
        self.assertIn("if (messageThread != null)", get_draft)
        self.assertIn("return messageThread.getDraft();", get_draft)
        self.assertIn(
            "messageTopicId.getConstructor() == TdApi.MessageTopicForum.CONSTRUCTOR",
            get_draft,
        )
        self.assertIn("return forumTopic != null ? forumTopic.draftMessage : null;", get_draft)
        self.assertIn("return chat != null ? chat.draftMessage : null;", get_draft)

        self.assertIn("private void applyForumTopicDraft (", messages)
        apply_topic_draft = java_method(messages, "private void applyForumTopicDraft (")
        self.assertIn("!inputView.textChangedSinceChatOpened()", apply_topic_draft)
        self.assertIn("updateDraftMessage(getChatId(), draftMessage)", apply_topic_draft)
        self.assertGreaterEqual(messages.count("applyForumTopicDraft("), 2)

        # Saving an empty composer sends null to this topic and updates the same local
        # ForumTopic object, so reopening cannot resurrect the list's stale draft.
        self.assertIn("forumTopic.draftMessage = savedDraftMessage", save_draft)
        self.assertIn("new TdApi.SetChatDraftMessage", save_draft)
        self.assertIn("topicId", save_draft)
        self.assertIn("!Td.isEmpty(draftMessage) ? draftMessage : null", save_draft)
        self.assertIn("savedDraftMessage", save_draft)

    def test_all_visible_topic_messages_are_eagerly_marked_read(self) -> None:
        manager = read(
            "app/src/main/java/org/thunderdog/challegram/component/chat/MessagesManager.java"
        )
        force_read = java_method(manager, "public boolean needForceRead (")

        # ViewMessages must not depend on a scroll gesture. Notification taps,
        # forum topics, private bot topics, and Replies all use the same read path.
        self.assertIn("return canRead();", force_read)
        self.assertNotIn("wasScrollByUser", force_read)
        self.assertNotIn("MessageTopicForum.CONSTRUCTOR", force_read)

        loader = read(
            "app/src/main/java/org/thunderdog/challegram/component/chat/MessagesLoader.java"
        )
        source = java_method(loader, "private TdApi.MessageSource newMessageSource ()")
        self.assertIn("new TdApi.MessageSourceForumTopicHistory()", source)

        messages = read(
            "app/src/main/java/org/thunderdog/challegram/ui/MessagesController.java"
        )
        topic_update = java_method(messages, "public void onForumTopicUpdated (")
        self.assertIn("new TdApi.GetForumTopic", topic_update)
        self.assertIn("tdlib.updateForumTopicUnreadCount", topic_update)
        self.assertNotIn("Estimate: decrease unread count", topic_update)

    def test_topic_notifications_target_messages_and_suppress_the_active_topic(self) -> None:
        group = read(
            "app/src/main/java/org/thunderdog/challegram/telegram/TdlibNotificationGroup.java"
        )
        target = java_method(group, "public long findTargetMessageId ()")
        self.assertIn("getMessageTopicId() == null", target)

        manager = read(
            "app/src/main/java/org/thunderdog/challegram/telegram/TdlibNotificationManager.java"
        )
        active = java_method(manager, "boolean isMessageFromActiveTopic (")
        self.assertIn("message.topicId == null", active)
        self.assertIn("messages.getActiveChatId() == message.chatId", active)
        self.assertIn("messages.compareChat(message.chatId, message.topicId)", active)

        helper = read(
            "app/src/main/java/org/thunderdog/challegram/telegram/TdlibNotificationHelper.java"
        )
        self.assertGreaterEqual(helper.count("context.isMessageFromActiveTopic("), 2)

    def test_replies_notification_opens_the_aggregate_inbox(self) -> None:
        activity = read("app/src/main/java/org/thunderdog/challegram/MainActivity.java")
        opener = java_method(activity, "private void openMessagesController (")
        replies = opener.index("if (isRepliesChat)")
        routing = opener.index("MessageTopicRouting.fromExternalMessageThreadId(")
        self.assertLess(replies, routing)

        replies_branch = opener[replies:routing]
        self.assertIn("params.highlightMessage(new MessageId(chatId, specificMessageId))", replies_branch)
        self.assertIn("params.ensureHighlightAvailable()", replies_branch)
        self.assertIn("tdlib.ui().openChat(context, chatId, params)", replies_branch)
        self.assertIn("return;", replies_branch)
        self.assertNotIn("params.messageTopic", replies_branch)
        self.assertNotIn("params.messageThread", replies_branch)

    def test_group_thread_notifications_open_history_instead_of_a_thread(self) -> None:
        activity = read("app/src/main/java/org/thunderdog/challegram/MainActivity.java")
        opener = java_method(activity, "private void openMessagesController (")
        self.assertIn("targetTopic.getConstructor() == TdApi.MessageTopicThread.CONSTRUCTOR ? null : targetTopic", opener)
        self.assertNotIn("new TdApi.GetMessageThread(chatId, specificMessageId)", opener)
        self.assertNotIn("params.messageThread", opener)

        messages = read(
            "app/src/main/java/org/thunderdog/challegram/ui/MessagesController.java"
        )
        matches = java_method(messages, "private boolean matchesTopic (")
        self.assertIn("getMessageTopicId()", matches)

    def test_topic_list_and_chat_list_share_authoritative_unread_state(self) -> None:
        tdlib = read("app/src/main/java/org/thunderdog/challegram/telegram/Tdlib.java")
        cache = java_method(tdlib, "public void updateForumTopicsCache (long chatId, List<TdApi.ForumTopic> topics, boolean isComplete)")
        store_count = java_method(tdlib, "private boolean storeForumUnreadTopicCount (")
        refresh = java_method(tdlib, "private void sendForumTopicRefresh (")
        new_message = java_method(tdlib, "private void updateForumTopicForNewMessage (")
        topic_update = java_method(tdlib, "private void updateForumTopic (TdApi.UpdateForumTopic")
        self.assertIn("countUnreadTopics(snapshot)", cache)
        self.assertIn("storeForumUnreadTopicCount(chatId, unreadCount)", cache)
        self.assertIn("!completeForumTopics.contains(chatId)", store_count)
        self.assertIn("settings().setForumUnreadTopicCount(chatId, count)", store_count)
        self.assertIn("topic.unreadCount++", new_message)
        self.assertIn("listeners().updateForumUnreadTopicCount", new_message)
        self.assertIn("refreshCachedForumTopic(update.chatId, update.forumTopicId)", topic_update)
        self.assertIn("new TdApi.GetForumTopic", refresh)
        self.assertIn("refresh.revision != revision", refresh)

        controller = read(
            "app/src/main/java/org/thunderdog/challegram/ui/ForumTopicsController.java"
        )
        controller_update = java_method(controller, "public void onForumTopicsChanged (")
        self.assertIn("isDestroyed()", controller_update)
        self.assertIn("showSharedTopics", controller_update)
        self.assertIn("ForumTopicOrder.update(topic)", new_message)
        self.assertIn("publishForumTopics(message.chatId)", new_message)

    def test_private_topic_reads_preserve_other_topics(self) -> None:
        tdlib = read("app/src/main/java/org/thunderdog/challegram/telegram/Tdlib.java")
        viewer = read("app/src/main/java/org/thunderdog/challegram/telegram/TdlibMessageViewer.java")
        self.assertNotIn("acknowledgePrivateTopicReadInParent", viewer + tdlib)
        acknowledge = java_method(tdlib, "private void acknowledgeReadPrivateTopics (")
        self.assertIn("!completeForumTopics.contains(chatId)", acknowledge)
        self.assertIn("forumTopicsRequests.containsKey(chatId)", acknowledge)
        self.assertIn("topic.unreadCount != 0", acknowledge)
        self.assertIn("topic.lastMessage.id > topic.lastReadInboxMessageId", acknowledge)
        self.assertIn("lastReadMessageId <= chat.lastReadInboxMessageId", acknowledge)

        topic_update = java_method(tdlib, "private void updateForumTopic (TdApi.UpdateForumTopic")
        self.assertIn("clearReadPrivateTopicNotifications(update.chatId, update.forumTopicId", topic_update)
        active = java_method(tdlib, "private void onUpdateActiveNotifications (")
        added = java_method(tdlib, "private void onUpdateNotificationGroup (")
        self.assertIn("loadPrivateNotificationTopics", active)
        self.assertIn("loadPrivateNotificationTopics", added)
        load = java_method(tdlib, "private void loadPrivateNotificationTopics (")
        self.assertIn("fetchForumUnreadTopicCount", load)
        self.assertIn("refreshCachedForumTopic", load)

        helper = read("app/src/main/java/org/thunderdog/challegram/telegram/TdlibNotificationHelper.java")
        remove_topic = java_method(helper, "public void removeTopicNotifications (")
        self.assertIn("Td.equalsTo(message.topicId, topicId)", remove_topic)
        self.assertIn("message.id <= lastReadInboxMessageId", remove_topic)
        self.assertIn("!message.isOutgoing", remove_topic)
        self.assertIn("group.isMention()", remove_topic)
        self.assertIn("new TdApi.RemoveNotification", remove_topic)
        self.assertNotIn("manager().cancel", remove_topic)
        for signature in ("public void restoreState (", "public void updateGroup (", "public void editNotification ("):
            self.assertIn("removeReadTopicNotification", java_method(helper, signature))

    def test_topic_notifications_use_one_authoritative_android_group(self) -> None:
        style = read(
            "app/src/main/java/org/thunderdog/challegram/telegram/TdlibNotificationStyle.java"
        )
        child = java_method(style, "protected final int displayChildNotification (")
        self.assertIn("cancelLegacyTopicNotifications(group)", child)
        self.assertNotIn("splitByForumTopics", child)
        self.assertNotIn("displayTopicNotification", style)
        self.assertNotIn("_forum_", style)

        group = read(
            "app/src/main/java/org/thunderdog/challegram/telegram/TdlibNotificationGroup.java"
        )
        self.assertNotIn("class TopicView", group)
        self.assertNotIn("hasMultipleForumTopics", group)
        self.assertNotIn("splitByForumTopics", group)

        helper = read(
            "app/src/main/java/org/thunderdog/challegram/telegram/TdlibNotificationHelper.java"
        )
        migration = java_method(helper, "private void cancelLegacyTopicNotifications ()")
        self.assertIn("getActiveNotifications()", migration)
        self.assertIn("_forum_", migration)

        group_cleanup = java_method(
            helper, "public void cancelLegacyTopicNotifications ("
        )
        self.assertIn("getAllForumTopicIds()", group_cleanup)

        update_group = java_method(helper, "public void updateGroup (")
        hide_group = java_method(helper, "private void hideNotificationGroup (")
        self.assertIn("cancelLegacyTopicNotifications(group)", update_group)
        self.assertIn("cancelLegacyTopicNotifications(group)", hide_group)


if __name__ == "__main__":
    unittest.main()
