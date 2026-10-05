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
 * File created for forum topics support
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.chat.ChatHeaderView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.Menu;
import org.thunderdog.challegram.navigation.MoreDelegate;
import org.thunderdog.challegram.navigation.TelegramViewController;
import org.thunderdog.challegram.support.ViewSupport;
import tgx.td.ChatId;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.telegram.ChatListener;
import org.thunderdog.challegram.telegram.NotificationSettingsListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibCache;
import org.thunderdog.challegram.telegram.TdlibSettingsManager;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.CircleButton;
import org.thunderdog.challegram.widget.ListInfoView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.StringUtils;
import me.vkryl.core.collection.IntList;
import org.thunderdog.challegram.util.StringList;
import org.thunderdog.challegram.navigation.SettingsWrapBuilder;
import org.thunderdog.challegram.util.TopicIconModifier;

import tgx.td.MessageId;
import tgx.td.Td;

public class ForumTopicsController extends TelegramViewController<ForumTopicsController.Arguments> implements
  Menu, MoreDelegate, View.OnClickListener, View.OnLongClickListener,
  ChatListener, TdlibCache.SupergroupDataChangeListener, ChatHeaderView.Callback,
  Settings.ChatListModeChangeListener, NotificationSettingsListener {

  public static class Arguments {
    public final long chatId;
    public final TdApi.Chat chat;

    public Arguments (long chatId, @Nullable TdApi.Chat chat) {
      this.chatId = chatId;
      this.chat = chat;
    }

    public Arguments (TdApi.Chat chat) {
      this.chatId = chat.id;
      this.chat = chat;
    }
  }

  public ForumTopicsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  private long chatId;
  private TdApi.Chat chat;

  private FrameLayoutFix contentView;
  private CustomRecyclerView recyclerView;
  private ForumTopicsAdapter adapter;
  private ListInfoView emptyView;
  private CircleButton createTopicButton;
  private ChatHeaderView headerCell;

  private List<TdApi.ForumTopic> topics;
  private List<TdApi.ForumTopic> allTopics; // For restoring after search
  private boolean isLoading;
  private int topicsLoadGeneration;
  private boolean isSubscribedToUpdates;
  private String currentSearchQuery;
  private boolean searchInMessages = true; // Toggle between topic name search and message search (default: messages)
  private List<TopicMessageSearchResult> messageSearchResults = new ArrayList<>();
  private List<TopicMessageSearchResult> unfilteredMessageResults = new ArrayList<>(); // Store original unfiltered results
  private boolean isSearchingMessages = false;
  private volatile int messageSearchGeneration;
  private long lastSearchMessageId = 0; // For message search pagination
  private boolean canLoadMoreMessages = false;
  private java.util.Set<Long> selectedFilterTopicIds = new java.util.HashSet<>(); // Empty = all topics
  private CircleButton filterTopicButton;
  private boolean navigatedFromSearch = false; // Track if we navigated to a topic from search results

  // Multi-page search loading
  private static final int PAGES_PER_LOAD = 10;  // Load 10 pages at a time for better topic filtering
  private static final int RESULTS_PER_PAGE = 100;
  private static final int MAX_AUTO_RETRY = 3;  // Max auto-retry when filtered results empty
  private int pendingPageLoads = 0;  // Track remaining pages to load
  private List<TdApi.Message> pendingMessages = new ArrayList<>();  // Collect results from all pages
  private String pendingSearchQuery = null;  // Query for current batch
  private int filterAutoRetryCount = 0;  // Track auto-retry attempts
  private java.util.Set<Long> pendingFilterTopicIds = null;  // Filter to apply after loading more

  @Override
  protected boolean allowLeavingSearchMode () {
    // Prevent leaving search mode if we navigated to a search result and are returning
    if (navigatedFromSearch && searchInMessages && !messageSearchResults.isEmpty()) {
      return false;
    }
    return true;
  }

  @Override
  public void setArguments (Arguments args) {
    super.setArguments(args);
    this.chatId = args.chatId;
    this.chat = args.chat;
  }

  @Override
  public int getId () {
    return R.id.controller_forumTopics;
  }

  @Override
  public CharSequence getName () {
    if (chat != null) {
      return chat.title;
    }
    return Lang.getString(R.string.Topics);
  }

  @Override
  protected int getBackButton () {
    return BackHeaderButton.TYPE_BACK;
  }

  @Override
  public View getCustomHeaderCell () {
    return headerCell;
  }

  @Override
  protected int getMenuId () {
    return R.id.menu_more;
  }

  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    if (id == R.id.menu_more) {
      header.addMoreButton(menu, this);
    }
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id == R.id.menu_btn_more) {
      showMoreOptions();
    }
  }

  private void showMoreOptions () {
    if (chat == null) {
      return;
    }

    IntList ids = new IntList(6);
    StringList strings = new StringList(6);

    TdApi.ChatMemberStatus status = tdlib.chatStatus(chat.id);
    if (tdlib.canEditOwnChatMemberTag(chat.id)) {
      ids.append(R.id.btn_editOwnMemberTag);
      strings.append(R.string.EditOwnMemberTag);
    }

    // Mute/Unmute option
    if (!tdlib.isChannel(chat.id) || (status != null && !TD.isLeft(status))) {
      ids.append(R.id.btn_mute);
      strings.append(tdlib.chatNotificationsEnabled(chat.id) ? R.string.Mute : R.string.Unmute);
    }

    // Report option
    if (tdlib.canReportChatSpam(chat.id)) {
      ids.append(R.id.btn_reportChat);
      strings.append(R.string.Report);
    }

    // Manage group option (for admins)
    if (status != null && TD.isAdmin(status) || tdlib.canChangeInfo(chat)) {
      ids.append(R.id.btn_manageGroup);
      strings.append(R.string.ManageGroup);
    }

    // Delete/Leave options
    tdlib.ui().addDeleteChatOptions(chat.id, ids, strings, !tdlib.isChannel(chat.id), false);

    // Supergroup forums can switch to a unified chat. TDLib intentionally doesn't support this for bot topics.
    if (tdlib.isForum(chat.id)) {
      ids.append(R.id.btn_viewAsChat);
      strings.append(R.string.ViewAsChat);
    }

    showMore(ids.get(), strings.get(), 0);
  }

  @Override
  protected void onLeaveSearchMode () {
    messageSearchGeneration++;
    isSearchingMessages = false;
    setClearButtonSearchInProgress(false);
    navigatedFromSearch = false; // Reset navigation flag when leaving search
    currentSearchQuery = null;
    searchInMessages = true; // Reset to default (messages mode)
    messageSearchResults.clear();
    unfilteredMessageResults.clear();
    selectedFilterTopicIds.clear();
    updateFilterFabVisibility();
    if (allTopics != null) {
      topics.clear();
      topics.addAll(allTopics);
      adapter.setTopics(topics, null);
      updateEmptyView();
    }
  }

  @Override
  protected void onSearchInputChanged (String query) {
    super.onSearchInputChanged(query);
    String cleanQuery = query != null ? query.trim() : "";
    if (StringUtils.equalsOrBothEmpty(cleanQuery, currentSearchQuery)) {
      return;
    }
    currentSearchQuery = cleanQuery;
    if (searchInMessages) {
      searchMessages(cleanQuery);
    } else {
      searchTopics(cleanQuery);
    }
  }

  private void toggleSearchMode () {
    messageSearchGeneration++;
    isSearchingMessages = false;
    setClearButtonSearchInProgress(false);
    searchInMessages = !searchInMessages;
    // Update toggle button icon
    if (headerView != null) {
      headerView.updateButton(R.id.menu_clear, R.id.btn_searchModeToggle, View.VISIBLE,
        searchInMessages ? R.drawable.baseline_chat_bubble_24 : R.drawable.baseline_forum_24);
    }

    // Reset filter when switching modes
    selectedFilterTopicIds.clear();
    unfilteredMessageResults.clear();

    // Re-search with the same query
    if (!StringUtils.isEmpty(currentSearchQuery)) {
      if (searchInMessages) {
        searchMessages(currentSearchQuery);
      } else {
        searchTopics(currentSearchQuery);
      }
    } else {
      // No query - restore original list if in topic mode, or show empty in message mode
      if (!searchInMessages && allTopics != null) {
        topics.clear();
        topics.addAll(allTopics);
        adapter.setTopics(topics, null);
        updateEmptyView();
      } else if (searchInMessages) {
        messageSearchResults.clear();
        adapter.setMessageSearchResults(messageSearchResults, null);
        updateEmptyView();
      }
    }

    // Update filter FAB visibility based on new mode
    updateFilterFabVisibility();
  }

  private void searchTopics (String query) {
    if (StringUtils.isEmpty(query)) {
      // Empty query - restore all topics
      if (allTopics != null) {
        topics.clear();
        topics.addAll(allTopics);
        adapter.setTopics(topics, null);
        updateEmptyView();
      }
      return;
    }

    // Save current topics before search if not already saved
    if (allTopics == null || allTopics.isEmpty()) {
      allTopics = new ArrayList<>(topics);
    }

    // Client-side filtering by topic name (case-insensitive)
    String lowerQuery = query.toLowerCase();
    List<TdApi.ForumTopic> filteredTopics = new ArrayList<>();
    for (TdApi.ForumTopic topic : allTopics) {
      if (topic.info.name.toLowerCase().contains(lowerQuery)) {
        filteredTopics.add(topic);
      }
    }

    topics.clear();
    topics.addAll(filteredTopics);
    adapter.setTopics(topics, query);
    updateEmptyView();
  }

  private void searchMessages (String query) {
    if (StringUtils.isEmpty(query)) {
      messageSearchGeneration++;
      isSearchingMessages = false;
      messageSearchResults.clear();
      lastSearchMessageId = 0;
      canLoadMoreMessages = false;
      adapter.setMessageSearchResults(messageSearchResults, null);
      updateEmptyView();
      return;
    }

    final int searchGeneration = ++messageSearchGeneration;
    isSearchingMessages = true;

    // Reset pagination for new search
    lastSearchMessageId = 0;
    canLoadMoreMessages = false;
    messageSearchResults.clear();

    // Reset multi-page loading state
    pendingMessages.clear();
    pendingPageLoads = PAGES_PER_LOAD;
    pendingSearchQuery = query;
    pendingFilterTopicIds = null;
    filterAutoRetryCount = 0;

    // Show loading state
    emptyView.setVisibility(View.VISIBLE);
    emptyView.showInfo(Lang.getString(R.string.LoadingTopics));
    setClearButtonSearchInProgress(true);

    // Start loading first page (will chain-load remaining pages)
    loadMessagePage(query, 0, false, searchGeneration);
  }

  private void loadMessagePage (String query, long fromMessageId, boolean isAppending,
    int searchGeneration) {
    tdlib.client().send(new TdApi.SearchChatMessages(
      chatId,
      null, // topicId - null to search all topics
      query,
      null, // senderId
      fromMessageId, // fromMessageId for pagination
      0, // offset
      RESULTS_PER_PAGE, // limit
      null // filter
    ), result -> UI.post(() -> handleMessageSearchPage(query, isAppending,
      searchGeneration, result)));
  }

  private void handleMessageSearchPage (String query, boolean isAppending,
    int searchGeneration, TdApi.Object result) {
    if (searchGeneration != messageSearchGeneration) return;
    if (result.getConstructor() == TdApi.FoundChatMessages.CONSTRUCTOR) {
      TdApi.FoundChatMessages foundMessages = (TdApi.FoundChatMessages) result;

      java.util.Collections.addAll(pendingMessages, foundMessages.messages);

      pendingPageLoads--;

      // Store pagination cursor for later "load more"
      lastSearchMessageId = foundMessages.nextFromMessageId;

      // Continue loading more pages if available and within limit
      if (foundMessages.nextFromMessageId != 0 && pendingPageLoads > 0) {
        loadMessagePage(query, foundMessages.nextFromMessageId, isAppending, searchGeneration);
      } else {
        // All pages loaded (or no more results)
        canLoadMoreMessages = foundMessages.nextFromMessageId != 0;
        finalizeBatchSearch(query, isAppending, searchGeneration);
      }
    } else {
      // Error - finalize with what we have
      canLoadMoreMessages = false;
      finalizeBatchSearch(query, isAppending, searchGeneration);
    }
  }

  private void finalizeBatchSearch (String query, boolean isAppending, int searchGeneration) {
    if (searchGeneration != messageSearchGeneration) return;
    // Convert collected messages to array and process
    TdApi.Message[] messages = pendingMessages.toArray(new TdApi.Message[0]);
    pendingMessages.clear();

    // Check if there's a pending filter to apply after processing
    final java.util.Set<Long> filterToApply = pendingFilterTopicIds;

    processMessageSearchResults(messages, query, isAppending, searchGeneration);

    // Re-apply pending filter if any (for auto-retry scenario)
    if (filterToApply != null && !filterToApply.isEmpty()) {
      UI.post(() -> {
        if (searchGeneration == messageSearchGeneration) applyTopicFilter(filterToApply, true);
      });
    }
  }

  private void loadMoreMessages () {
    if (isSearchingMessages || !canLoadMoreMessages || StringUtils.isEmpty(currentSearchQuery)) {
      return;
    }
    isSearchingMessages = true;
    final int searchGeneration = ++messageSearchGeneration;

    // Reset multi-page loading state for batch load
    pendingMessages.clear();
    pendingPageLoads = PAGES_PER_LOAD;
    pendingSearchQuery = currentSearchQuery;

    // Start loading from last position (will chain-load remaining pages)
    loadMessagePage(currentSearchQuery, lastSearchMessageId, true, searchGeneration);
  }

  private void processMessageSearchResults (TdApi.Message[] messages, String query, boolean append,
    int searchGeneration) {
    // Flat list: show ALL messages, not grouped by topic
    if (messages.length == 0) {
      UI.post(() -> {
        if (searchGeneration != messageSearchGeneration) return;
        isSearchingMessages = false;
        setClearButtonSearchInProgress(false);
        if (!append) {
          messageSearchResults.clear();
          adapter.setMessageSearchResults(messageSearchResults, query);
        }
        updateEmptyViewForMessageSearch();
      });
      return;
    }

    // Collect unique topic IDs to fetch
    Map<Long, TdApi.ForumTopic> topicCache = new HashMap<>();
    java.util.Set<Long> topicIdsToFetch = new java.util.HashSet<>();

    // First pass: check cache and collect IDs to fetch
    for (TdApi.Message message : messages) {
      long topicId = 0;
      if (message.topicId != null && message.topicId instanceof TdApi.MessageTopicForum) {
        topicId = ((TdApi.MessageTopicForum) message.topicId).forumTopicId;
      }
      if (topicId != 0 && !topicCache.containsKey(topicId)) {
        // Check cached allTopics first
        TdApi.ForumTopic cachedTopic = null;
        if (allTopics != null) {
          for (TdApi.ForumTopic t : allTopics) {
            if (t.info.forumTopicId == topicId) {
              cachedTopic = t;
              break;
            }
          }
        }
        if (cachedTopic != null) {
          topicCache.put(topicId, cachedTopic);
        } else {
          topicIdsToFetch.add(topicId);
        }
      }
    }

    // If all topics are cached, build results directly
    if (topicIdsToFetch.isEmpty()) {
      buildFlatMessageResults(messages, topicCache, query, append, searchGeneration);
      return;
    }

    // Fetch missing topics
    int[] pending = {topicIdsToFetch.size()};
    boolean finalAppend = append;
    for (Long topicId : topicIdsToFetch) {
      tdlib.client().send(new TdApi.GetForumTopic(chatId, topicId.intValue()), topicResult -> UI.post(() -> {
        if (searchGeneration != messageSearchGeneration) return;
        if (topicResult.getConstructor() == TdApi.ForumTopic.CONSTRUCTOR) {
          TdApi.ForumTopic topic = (TdApi.ForumTopic) topicResult;
          topicCache.put(topicId, topic);
        }
        pending[0]--;
        if (pending[0] == 0) {
          buildFlatMessageResults(messages, topicCache, query, finalAppend, searchGeneration);
        }
      }));
    }
  }

  private void buildFlatMessageResults (TdApi.Message[] messages, Map<Long, TdApi.ForumTopic> topicCache,
    String query, boolean append, int searchGeneration) {
    if (searchGeneration != messageSearchGeneration) return;
    List<TopicMessageSearchResult> results = new ArrayList<>();
    java.util.Set<Long> seenMessageIds = new java.util.HashSet<>();
    for (TdApi.Message message : messages) {
      if (!seenMessageIds.add(message.id)) continue;
      long topicId = 0;
      if (message.topicId != null && message.topicId instanceof TdApi.MessageTopicForum) {
        topicId = ((TdApi.MessageTopicForum) message.topicId).forumTopicId;
      }
      TdApi.ForumTopic topic = topicCache.get(topicId);
      if (topic != null) {
        results.add(new TopicMessageSearchResult(topic, message, query));
      }
    }
    finalizeMessageSearchResults(results, query, append, searchGeneration);
  }

  private void finalizeMessageSearchResults (List<TopicMessageSearchResult> results, String query,
    boolean append, int searchGeneration) {
    UI.post(() -> {
      if (searchGeneration != messageSearchGeneration) return;
      List<TopicMessageSearchResult> effectiveResults = results;
      isSearchingMessages = false;
      setClearButtonSearchInProgress(false);
      if (!append) {
        // New search - store in unfiltered list and reset filter
        unfilteredMessageResults.clear();
        unfilteredMessageResults.addAll(effectiveResults);
        selectedFilterTopicIds.clear();
        messageSearchResults.clear();
        messageSearchResults.addAll(effectiveResults);
      } else {
        // Pagination - add to unfiltered list
        java.util.Set<Long> existingMessageIds = new java.util.HashSet<>();
        for (TopicMessageSearchResult result : unfilteredMessageResults) {
          existingMessageIds.add(result.foundMessage.id);
        }
        List<TopicMessageSearchResult> uniqueResults = new ArrayList<>();
        for (TopicMessageSearchResult result : effectiveResults) {
          if (existingMessageIds.add(result.foundMessage.id)) uniqueResults.add(result);
        }
        effectiveResults = uniqueResults;
        unfilteredMessageResults.addAll(effectiveResults);
        // If filter is active, only add results that match
        if (!selectedFilterTopicIds.isEmpty()) {
          for (TopicMessageSearchResult result : effectiveResults) {
            long topicId = result.topic.info.forumTopicId;
            if (selectedFilterTopicIds.contains(topicId)) {
              messageSearchResults.add(result);
            }
          }
        } else {
          messageSearchResults.addAll(effectiveResults);
        }
      }

      if (append && !effectiveResults.isEmpty()) {
        // Notify only about new items for better performance
        int insertedCount = (!selectedFilterTopicIds.isEmpty())
          ? (int) effectiveResults.stream().filter(r -> selectedFilterTopicIds.contains((long) r.topic.info.forumTopicId)).count()
          : effectiveResults.size();
        if (insertedCount > 0) {
          adapter.notifyItemRangeInserted(messageSearchResults.size() - insertedCount, insertedCount);
        }
      } else {
        adapter.setMessageSearchResults(messageSearchResults, query);
      }
      updateEmptyViewForMessageSearch();

      // Show filter FAB when there are results
      updateFilterFabVisibility();
    });
  }

  private void updateEmptyViewForMessageSearch () {
    if (messageSearchResults.isEmpty()) {
      emptyView.setVisibility(View.VISIBLE);
      emptyView.showInfo(Lang.getString(R.string.NoMessagesFound));
    } else {
      emptyView.setVisibility(View.GONE);
    }
  }

  private void updateFilterFabVisibility () {
    if (filterTopicButton == null) return;

    // Show filter FAB only when in message search mode with results
    boolean shouldShow = searchInMessages && !unfilteredMessageResults.isEmpty() && inSearchMode();
    filterTopicButton.setVisibility(shouldShow ? View.VISIBLE : View.GONE);

    // Reset FAB color if filter was cleared
    if (!shouldShow || selectedFilterTopicIds.isEmpty()) {
      filterTopicButton.init(R.drawable.baseline_tune_24, 56f, 4f, ColorId.circleButtonRegular, ColorId.circleButtonRegularIcon);
    }
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (inSearchMode()) {
      if (commit) {
        closeSearchMode(null);
      }
      return true;
    }
    return super.performOnBackPressed(fromTop, commit);
  }

  @Override
  protected View onCreateView (Context context) {
    contentView = new FrameLayoutFix(context);
    contentView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    ViewSupport.setThemedBackground(contentView, ColorId.filling);
    addThemeInvalidateListener(contentView);

    // Create header view for clickable chat header
    headerCell = new ChatHeaderView(context, tdlib, this);
    headerCell.setCallback(this);
    // Register theme listeners for title and subtitle colors
    getThemeListeners().addThemeDoubleTextColorListener(headerCell, ColorId.headerText, ColorId.headerText);
    // Set inner margins to prevent title overlap with menu buttons
    // Right margin: 48dp (more button) + 48dp (search button) + 8dp (padding) = 104dp
    headerCell.setInnerMargins(Screen.dp(56f), Screen.dp(104f));
    if (chat != null) {
      headerCell.setChat(tdlib, chat, null, null);
    }

    topics = new ArrayList<>();
    adapter = new ForumTopicsAdapter(this);

    recyclerView = new CustomRecyclerView(context);
    recyclerView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    recyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
    recyclerView.setAdapter(adapter);
    recyclerView.setItemAnimator(null);
    addThemeInvalidateListener(recyclerView);
    recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled (@NonNull RecyclerView recyclerView, int dx, int dy) {
        LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (manager == null) return;

        int lastVisible = manager.findLastVisibleItemPosition();

        // Handle message search pagination
        if (inSearchMode() && searchInMessages && canLoadMoreMessages && !isSearchingMessages) {
          if (lastVisible >= messageSearchResults.size() - 5) {
            loadMoreMessages();
          }
        }

      }
    });

    emptyView = new ListInfoView(context);
    emptyView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    emptyView.showInfo(Lang.getString(R.string.LoadingTopics));
    addThemeInvalidateListener(emptyView);

    contentView.addView(emptyView);
    contentView.addView(recyclerView);

    // Create topic FAB button
    int padding = Screen.dp(4f);
    FrameLayoutFix.LayoutParams fabParams = FrameLayoutFix.newParams(
      Screen.dp(56f) + padding * 2,
      Screen.dp(56f) + padding * 2,
      Gravity.RIGHT | Gravity.BOTTOM
    );
    fabParams.rightMargin = fabParams.bottomMargin = Screen.dp(16f) - padding;

    createTopicButton = new CircleButton(context);
    createTopicButton.setId(R.id.btn_createTopic);
    createTopicButton.setOnClickListener(this);
    createTopicButton.init(R.drawable.baseline_add_24, 56f, 4f, ColorId.circleButtonRegular, ColorId.circleButtonRegularIcon);
    createTopicButton.setLayoutParams(fabParams);
    addThemeInvalidateListener(createTopicButton);
    contentView.addView(createTopicButton);
    // Hide FAB if user can't create topics
    createTopicButton.setVisibility(canCreateTopics() ? View.VISIBLE : View.GONE);

    // Filter topic FAB button (positioned above create button)
    FrameLayoutFix.LayoutParams filterFabParams = FrameLayoutFix.newParams(
      Screen.dp(56f) + padding * 2,
      Screen.dp(56f) + padding * 2,
      Gravity.RIGHT | Gravity.BOTTOM
    );
    filterFabParams.rightMargin = Screen.dp(16f) - padding;
    filterFabParams.bottomMargin = Screen.dp(80f) - padding; // Above create button

    filterTopicButton = new CircleButton(context);
    filterTopicButton.setId(R.id.btn_filterTopic);
    filterTopicButton.setOnClickListener(this);
    filterTopicButton.init(R.drawable.baseline_tune_24, 56f, 4f, ColorId.circleButtonRegular, ColorId.circleButtonRegularIcon);
    filterTopicButton.setLayoutParams(filterFabParams);
    addThemeInvalidateListener(filterTopicButton);
    contentView.addView(filterTopicButton);
    // Initially hidden - show only during message search mode
    filterTopicButton.setVisibility(View.GONE);

    // Register for chat list mode changes
    Settings.instance().addChatListModeListener(this);

    loadTopics();

    return contentView;
  }

  @Override
  public void destroy () {
    super.destroy();
    topicsLoadGeneration++;
    messageSearchGeneration++;
    Settings.instance().removeChatListModeListener(this);
    if (isSubscribedToUpdates) {
      tdlib.listeners().unsubscribeFromChatUpdates(chatId, this);
      tdlib.listeners().unsubscribeFromSettingsUpdates(chatId, this);
      tdlib.listeners().unsubscribeFromSettingsUpdates(this);
      isSubscribedToUpdates = false;
    }
  }

  @Override
  public void onMoreItemPressed (int id) {
    if (id == R.id.btn_editOwnMemberTag) {
      tdlib.ui().editChatMemberTag(this, chatId, tdlib.myUserId());
      return;
    }
    // Handle delete/leave/return options using TdlibUi helper
    if (tdlib.ui().processLeaveButton(this, null, chatId, id, null)) {
      return;
    }

    if (id == R.id.btn_viewAsChat) {
      // Set viewAsTopics to false and open as unified chat
      tdlib.client().send(new TdApi.ToggleChatViewAsTopics(chatId, false), result -> {
        if (result.getConstructor() == TdApi.Ok.CONSTRUCTOR) {
          tdlib.ui().post(() -> {
            // Create MessagesController directly to prevent re-opening in tabs mode
            MessagesController messagesController = new MessagesController(context, tdlib);
            messagesController.setArguments(new MessagesController.Arguments(null, chat, null, null, null, 0, null));
            // Remove this controller from stack after transition
            messagesController.addOneShotFocusListener(() -> {
              messagesController.destroyStackItemAt(messagesController.stackSize() - 2);
            });
            navigateTo(messagesController);
          });
        }
      });
    } else if (id == R.id.btn_mute) {
      // Toggle mute/unmute
      tdlib.ui().toggleMute(this, chatId, false, null);
    } else if (id == R.id.btn_reportChat) {
      // Report chat
      TdlibUi.reportChat(this, chatId, null, null, null, true);
    } else if (id == R.id.btn_manageGroup) {
      // Open group management (profile)
      ProfileController profileController = new ProfileController(context, tdlib);
      profileController.setArguments(new ProfileController.Args(chat, null, false));
      navigateTo(profileController);
    }
  }
  @Override
  public void onChatHeaderClick () {
    if (chat != null) {
      ProfileController controller = new ProfileController(context, tdlib);
      controller.setShareCustomHeaderView(true);
      controller.setArguments(new ProfileController.Args(chat, null, false));
      navigateTo(controller);
    }
  }

  @Override
  public void onChatListModeChanged (int newChatListMode) {
    // Update all visible ForumTopicView items to use the new chat list mode
    if (recyclerView != null && adapter != null) {
      // Update view heights for all items
      for (int i = 0; i < recyclerView.getChildCount(); i++) {
        View child = recyclerView.getChildAt(i);
        if (child instanceof ForumTopicView) {
          ForumTopicView topicView = (ForumTopicView) child;
          // Update layout params with new height
          RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) topicView.getLayoutParams();
          params.height = ForumTopicView.getViewHeight(newChatListMode);
          topicView.setLayoutParams(params);
          // Trigger rebuild of text layouts with new mode
          topicView.requestLayout();
        }
      }
      // Notify adapter to rebind all items with new heights
      adapter.notifyDataSetChanged();
    }
  }

  private void loadTopics () {
    if (isLoading) return;
    isLoading = true;
    if (!isSubscribedToUpdates) {
      tdlib.listeners().subscribeToChatUpdates(chatId, this);
      tdlib.listeners().subscribeToSettingsUpdates(chatId, this);
      tdlib.listeners().subscribeToSettingsUpdates(this);
      isSubscribedToUpdates = true;
    }
    showSharedTopics(false);
    final int generation = ++topicsLoadGeneration;
    // The shared scan owns pagination and publishes each page. Never write a UI
    // snapshot back into that cache: it may already contain newer live updates.
    tdlib.loadForumTopicsFirstPage(chatId, result -> UI.post(() -> {
      if (generation != topicsLoadGeneration || isDestroyed()) return;
      isLoading = false;
      showSharedTopics(false);
      if (!inSearchMode()) updateEmptyView();
    }));
  }

  private void showSharedTopics (boolean useDiff) {
    List<TdApi.ForumTopic> snapshot = tdlib.getCachedForumTopics(chatId);
    if (snapshot == null) return;
    allTopics = snapshot;
    if (!inSearchMode()) {
      topics.clear();
      topics.addAll(snapshot);
      adapter.setTopics(topics, null, useDiff);
      updateEmptyView();
    } else if (!searchInMessages) {
      searchTopics(currentSearchQuery);
    }
  }

  @Override
  public void onForumTopicsChanged (long chatId) {
    if (chatId != this.chatId) return;
    UI.post(() -> {
      if (!isDestroyed() && topics != null) {
        showSharedTopics(!tdlib.isLoadingForumTopics(chatId));
      }
    });
  }

  private static int findTopic (List<TdApi.ForumTopic> list, int topicId) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).info.forumTopicId == topicId) return i;
    }
    return -1;
  }

  private void updateEmptyView () {
    if (topics.isEmpty()) {
      emptyView.setVisibility(View.VISIBLE);
      emptyView.showInfo(Lang.getString(R.string.NoTopics));
    } else {
      emptyView.setVisibility(View.GONE);
    }
  }

  @Override
  public void onClick (View v) {
    int id = v.getId();
    if (id == R.id.btn_createTopic) {
      showCreateTopicDialog();
      return;
    }
    if (id == R.id.btn_filterTopic) {
      showTopicFilterOptions();
      return;
    }
    Object tag = v.getTag();
    if (tag instanceof TopicMessageSearchResult) {
      TopicMessageSearchResult result = (TopicMessageSearchResult) tag;
      openTopicAtMessage(result.topic, result.foundMessage);
    } else if (tag instanceof TdApi.ForumTopic) {
      TdApi.ForumTopic topic = (TdApi.ForumTopic) tag;
      openTopic(topic);
    }
  }

  @Override
  public boolean onLongClick (View v) {
    Object tag = v.getTag();
    if (tag instanceof TdApi.ForumTopic) {
      TdApi.ForumTopic topic = (TdApi.ForumTopic) tag;
      showTopicOptions(topic);
      return true;
    }
    return false;
  }

  private void openTopic (TdApi.ForumTopic topic) {
    MessagesController controller = new MessagesController(context, tdlib);

    // Calculate highlight position based on topic's last read message
    MessageId highlightMessageId = null;
    int highlightMode = MessagesManager.HIGHLIGHT_MODE_NONE;

    if (topic.unreadCount > 0 && topic.lastReadInboxMessageId != 0) {
      // There are unread messages - scroll to first unread
      highlightMessageId = new MessageId(chatId, topic.lastReadInboxMessageId);
      highlightMode = MessagesManager.HIGHLIGHT_MODE_UNREAD;
    } else if (topic.lastReadInboxMessageId == 0 && topic.unreadCount > 0) {
      // No messages have been read yet - scroll to beginning
      highlightMessageId = new MessageId(chatId,
        MessageId.fromServerMessageId(topic.info.forumTopicId));
      highlightMode = MessagesManager.HIGHLIGHT_MODE_UNREAD;
    }
    // If all messages are read (unreadCount == 0), highlightMessageId stays null
    // which will open at the bottom (most recent messages)

    MessagesController.Arguments args = new MessagesController.Arguments(
      null, // chatList
      chat,
      null, // threadInfo - will use topicId instead
      new TdApi.MessageTopicForum(topic.info.forumTopicId),
      highlightMessageId,
      highlightMode,
      null // filter
    );
    args.setForumTopic(topic);
    controller.setArguments(args);
    navigateTo(controller);
  }

  private void openTopicAtMessage (TdApi.ForumTopic topic, TdApi.Message message) {
    MessagesController controller = new MessagesController(context, tdlib);

    // Navigate to the specific found message with highlight
    MessageId highlightMessageId = new MessageId(chatId, message.id);
    int highlightMode = MessagesManager.HIGHLIGHT_MODE_NORMAL;

    MessagesController.Arguments args = new MessagesController.Arguments(
      null, // chatList
      chat,
      null, // threadInfo - will use topicId instead
      new TdApi.MessageTopicForum(topic.info.forumTopicId),
      highlightMessageId,
      highlightMode,
      null // filter
    );
    args.setForumTopic(topic);
    controller.setArguments(args);
    navigatedFromSearch = true; // Mark that we're navigating from search results
    navigateTo(controller);
  }

  private void showTopicOptions (TdApi.ForumTopic topic) {
    IntList ids = new IntList(5);
    IntList icons = new IntList(5);
    IntList colors = new IntList(5);
    ArrayList<String> strings = new ArrayList<>();

    boolean canManage = canManageTopics();
    boolean canDelete = canDeleteTopics();

    // Admin-only: Pin/Unpin
    if (canManage) {
      if (topic.isPinned) {
        ids.append(R.id.btn_unpinTopic);
        icons.append(R.drawable.deproko_baseline_pin_undo_24);
        colors.append(OptionColor.NORMAL);
        strings.add(Lang.getString(R.string.UnpinTopic));
      } else {
        ids.append(R.id.btn_pinTopic);
        icons.append(R.drawable.deproko_baseline_pin_24);
        colors.append(OptionColor.NORMAL);
        strings.add(Lang.getString(R.string.PinTopic));
      }

      // Admin-only: Close/Reopen
      if (topic.info.isClosed) {
        ids.append(R.id.btn_reopenTopic);
        icons.append(R.drawable.baseline_lock_24);
        colors.append(OptionColor.NORMAL);
        strings.add(Lang.getString(R.string.ReopenTopic));
      } else {
        ids.append(R.id.btn_closeTopic);
        icons.append(R.drawable.baseline_lock_24);
        colors.append(OptionColor.NORMAL);
        strings.add(Lang.getString(R.string.CloseTopic));
      }
    }

    // Admin-only: Edit
    if (canManage) {
      ids.append(R.id.btn_editTopic);
      icons.append(R.drawable.baseline_edit_24);
      colors.append(OptionColor.NORMAL);
      strings.add(Lang.getString(R.string.EditTopic));

      // Admin-only: Change Icon (not for General topic)
      if (!topic.info.isGeneral) {
        ids.append(R.id.btn_editTopicIcon);
        icons.append(R.drawable.baseline_palette_24);
        colors.append(OptionColor.NORMAL);
        strings.add(Lang.getString(R.string.ChangeTopicIcon));
      }
    }

    // Private bot capability permits creation and deletion, but not forum administration.
    if (canDelete && !topic.info.isGeneral) {
      ids.append(R.id.btn_deleteTopic);
      icons.append(R.drawable.baseline_delete_24);
      colors.append(OptionColor.RED);
      strings.add(Lang.getString(R.string.DeleteTopic));
    }

    showOptions(topic.info.name, ids.get(), strings.toArray(new String[0]), colors.get(), icons.get(), (itemView, id) -> {
      if (id == R.id.btn_pinTopic) {
        toggleTopicPinned(topic, true);
      } else if (id == R.id.btn_unpinTopic) {
        toggleTopicPinned(topic, false);
      } else if (id == R.id.btn_closeTopic) {
        toggleTopicClosed(topic, true);
      } else if (id == R.id.btn_reopenTopic) {
        toggleTopicClosed(topic, false);
      } else if (id == R.id.btn_editTopic) {
        editTopic(topic);
      } else if (id == R.id.btn_editTopicIcon) {
        showTopicIconPicker(topic);
      } else if (id == R.id.btn_deleteTopic) {
        deleteTopic(topic);
      }
      return true;
    });
  }

  private void toggleTopicPinned (TdApi.ForumTopic topic, boolean pinned) {
    tdlib.client().send(new TdApi.ToggleForumTopicIsPinned(topic.info.chatId, topic.info.forumTopicId, pinned), result -> {
      if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
        UI.post(() -> UI.showError(result));
      }
    });
  }

  private void toggleTopicClosed (TdApi.ForumTopic topic, boolean closed) {
    tdlib.client().send(new TdApi.ToggleForumTopicIsClosed(topic.info.chatId, topic.info.forumTopicId, closed), result -> {
      if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
        UI.post(() -> UI.showError(result));
      }
    });
  }

  private void editTopic (TdApi.ForumTopic topic) {
    openInputAlert(
      Lang.getString(R.string.EditTopic),
      Lang.getString(R.string.TopicNameHint),
      R.string.Done,
      R.string.Cancel,
      topic.info.name,
      (inputView, result) -> {
        String newName = result.trim();
        if (newName.isEmpty()) {
          inputView.setInErrorState(true);
          return false;
        }
        if (newName.length() > 128) {
          inputView.setInErrorState(true);
          return false;
        }
        // Edit the topic with new name, keep existing icon
        tdlib.client().send(new TdApi.EditForumTopic(
          topic.info.chatId,
          topic.info.forumTopicId,
          newName,
          false, // editIconCustomEmoji
          0 // iconCustomEmojiId (not changing)
        ), result1 -> {
          UI.post(() -> {
            if (result1.getConstructor() == TdApi.Ok.CONSTRUCTOR) {
              // Topic edited successfully, will be updated via listener
            } else if (result1.getConstructor() == TdApi.Error.CONSTRUCTOR) {
              UI.showError(result1);
            }
          });
        });
        return true;
      },
      true
    );
  }

  private void showTopicIconPicker (TdApi.ForumTopic topic) {
    // First, load available default topic icons from TDLib
    tdlib.client().send(new TdApi.GetForumTopicDefaultIcons(), result -> {
      UI.post(() -> {
        if (result.getConstructor() == TdApi.Stickers.CONSTRUCTOR) {
          TdApi.Stickers stickers = (TdApi.Stickers) result;
          showTopicIconPickerWithStickers(topic, stickers.stickers);
        } else if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
          UI.showError(result);
        }
      });
    });
  }

  private void showTopicIconPickerWithStickers (TdApi.ForumTopic topic, TdApi.Sticker[] stickers) {
    // Build options list
    // First option: Reset to colored circle (if topic has custom emoji)
    boolean hasCustomEmoji = topic.info.icon != null && topic.info.icon.customEmojiId != 0;

    IntList ids = new IntList(15);
    ArrayList<String> strings = new ArrayList<>();
    IntList iconsList = new IntList(15);

    if (hasCustomEmoji) {
      ids.append(R.id.btn_resetIcon);
      strings.add(Lang.getString(R.string.ResetTopicIcon));
      iconsList.append(R.drawable.baseline_undo_24);
    }

    // Add sticker options (limit to reasonable number)
    int maxStickers = Math.min(stickers.length, 12);
    for (int i = 0; i < maxStickers; i++) {
      ids.append(R.id.btn_stickerIcon0 + i);
      // Use emoji as label if available
      strings.add(stickers[i].emoji != null ? stickers[i].emoji : "Icon " + (i + 1));
      iconsList.append(0); // No drawable icon, uses text
    }

    showOptions(
      Lang.getString(R.string.ChangeTopicIcon),
      ids.get(),
      strings.toArray(new String[0]),
      null,
      iconsList.get(),
      (itemView, id) -> {
        if (id == R.id.btn_resetIcon) {
          // Reset to colored circle
          setTopicIcon(topic, 0);
        } else {
          // Set custom emoji icon
          int stickerIndex = id - R.id.btn_stickerIcon0;
          if (stickerIndex >= 0 && stickerIndex < stickers.length) {
            // The sticker id is the custom emoji identifier for custom emoji stickers
            setTopicIcon(topic, stickers[stickerIndex].id);
          }
        }
        return true;
      }
    );
  }

  private void setTopicIcon (TdApi.ForumTopic topic, long customEmojiId) {
    tdlib.client().send(new TdApi.EditForumTopic(
      topic.info.chatId,
      topic.info.forumTopicId,
      topic.info.name,
      true, // editIconCustomEmoji
      customEmojiId // 0 = reset to colored circle, non-zero = custom emoji
    ), result -> {
      UI.post(() -> {
        if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
          UI.showError(result);
        }
      });
    });
  }

  private void deleteTopic (TdApi.ForumTopic topic) {
    showConfirm(Lang.getStringBold(R.string.DeleteTopicConfirm, topic.info.name), Lang.getString(R.string.Delete), R.drawable.baseline_delete_24, OptionColor.RED, () -> {
      tdlib.client().send(new TdApi.DeleteForumTopic(topic.info.chatId, topic.info.forumTopicId), result -> {
        if (result.getConstructor() == TdApi.Ok.CONSTRUCTOR) {
          // Remove from local list immediately
          UI.post(() -> {
            tdlib.removeCachedForumTopic(chatId, topic.info.forumTopicId);
            for (int i = 0; i < topics.size(); i++) {
              if (topics.get(i).info.forumTopicId == topic.info.forumTopicId) {
                topics.remove(i);
                if (allTopics != null) {
                  int allIndex = findTopic(allTopics, topic.info.forumTopicId);
                  if (allIndex >= 0) allTopics.remove(allIndex);
                }
                if (adapter.isShowingTopics()) adapter.setTopics(topics, null);
                updateEmptyView();
                break;
              }
            }
          });
        } else if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
          UI.post(() -> UI.showError(result));
        }
      });
    });
  }

  private void showTopicFilterOptions () {
    // Use all topics from allTopics list
    if (allTopics == null || allTopics.isEmpty()) {
      return; // No topics to filter by
    }

    List<TdApi.ForumTopic> availableTopics = allTopics;

    // Track current selections - start with all topics if no filter, or current filter
    final java.util.Set<Long> currentSelections = new java.util.HashSet<>();
    if (selectedFilterTopicIds.isEmpty()) {
      // No filter = all topics selected
      for (TdApi.ForumTopic topic : availableTopics) {
        long topicId = topic.info.forumTopicId;
        currentSelections.add(topicId);
      }
    } else {
      currentSelections.addAll(selectedFilterTopicIds);
    }

    // Build checkbox items for multi-select
    List<ListItem> items = new ArrayList<>(availableTopics.size() + 2);
    items.add(new ListItem(ListItem.TYPE_PADDING).setHeight(Screen.dp(12f)).setBoolValue(true));

    // Add each topic as a checkbox option with topic icon
    for (TdApi.ForumTopic topic : availableTopics) {
      long topicId = topic.info.forumTopicId;
      boolean isSelected = currentSelections.contains(topicId);
      // Use TopicIconModifier to draw the actual topic icon (colored circle or custom emoji)
      TopicIconModifier iconModifier = new TopicIconModifier(tdlib, topic.info.icon);
      // Icon is drawn on the left by the modifier, add padding for icon space
      String displayName = "        " + topic.info.name;
      items.add(new ListItem(
        ListItem.TYPE_CHECKBOX_OPTION,
        (int) topicId, // id
        0, // icon
        displayName, // string with space prefix for icon
        (int) topicId, // checkId (same as id for multi-select)
        isSelected
      ).setLongValue(topicId).setDrawModifier(iconModifier));
    }

    items.add(new ListItem(ListItem.TYPE_PADDING).setHeight(Screen.dp(12f)).setBoolValue(true));

    final int totalTopicCount = availableTopics.size();

    SettingsWrapBuilder b = new SettingsWrapBuilder(R.id.btn_filterTopic)
      .addHeaderItem(Lang.getString(R.string.FilterByTopic))
      .setRawItems(items)
      .setSaveStr(Lang.getString(R.string.Done))
      .setNeedSeparators(false)
      .setSettingProcessor((item, view, isUpdate) -> {
        // Apply the DrawModifier to render topic icons
        view.setDrawModifier(item.getDrawModifier());
      })
      .setOnSettingItemClick((view, settingsId, item, doneButton, settingsAdapter, window) -> {
        // Update our tracked selections when checkbox is toggled
        if (item.getViewType() == ListItem.TYPE_CHECKBOX_OPTION) {
          long topicId = item.getLongValue();
          if (currentSelections.contains(topicId)) {
            currentSelections.remove(topicId);
          } else {
            currentSelections.add(topicId);
          }
        }
      })
      .setIntDelegate((id, result) -> {
        // Use our tracked selections instead of the result array
        if (currentSelections.size() == totalTopicCount) {
          // All selected = no filter
          applyTopicFilter(new java.util.HashSet<>());
        } else {
          applyTopicFilter(new java.util.HashSet<>(currentSelections));
        }
      });

    showSettings(b);
  }

  // Map topic color to a Unicode circle emoji for display in filter dialog
  private String getTopicColorEmoji (int colorValue) {
    // Telegram topic default colors mapped to emoji circles
    // Blue 0x6FB9F0, Yellow 0xFFD67E, Purple 0xCB86DB, Green 0x8EEE98, Pink 0xFF93B2, Red 0xFB6F5F

    // Normalize color (remove alpha if present)
    int color = colorValue & 0x00FFFFFF;

    // Check for custom emoji (iconCustomEmojiId != 0) - use white circle as default
    if (colorValue == 0) {
      return "\u26AA"; // White circle
    }

    // Map based on hue - determine closest match
    int r = (color >> 16) & 0xFF;
    int g = (color >> 8) & 0xFF;
    int b = color & 0xFF;

    // Simple color matching based on dominant channel
    if (b > r && b > g) {
      return "\uD83D\uDD35"; // Blue circle
    } else if (r > g && r > b && g > b * 0.8) {
      // Yellow/Orange (high red and green, low blue)
      return "\uD83D\uDFE1"; // Yellow circle
    } else if (r > b && g > b && Math.abs(r - g) < 50) {
      // Could be yellow or green - check green dominance
      if (g > r) {
        return "\uD83D\uDFE2"; // Green circle
      }
      return "\uD83D\uDFE1"; // Yellow circle
    } else if (g > r && g > b) {
      return "\uD83D\uDFE2"; // Green circle
    } else if (r > g && b > g * 0.5) {
      // Purple/Pink (high red and blue)
      if (b > r * 0.7) {
        return "\uD83D\uDFE3"; // Purple circle
      }
      return "\uD83D\uDD34"; // Red circle (for pink)
    } else if (r > g && r > b) {
      return "\uD83D\uDD34"; // Red circle
    }

    return "\u26AA"; // White circle as fallback
  }

  private void applyTopicFilter (java.util.Set<Long> topicIds) {
    applyTopicFilter(topicIds, false);
  }

  private void applyTopicFilter (java.util.Set<Long> topicIds, boolean isAutoRetry) {
    selectedFilterTopicIds = topicIds;

    // Reset retry counter on manual filter change
    if (!isAutoRetry) {
      filterAutoRetryCount = 0;
    }

    // Update FAB appearance based on filter state
    if (filterTopicButton != null) {
      if (!topicIds.isEmpty()) {
        // Filter is active - use accent color
        filterTopicButton.init(R.drawable.baseline_tune_24, 56f, 4f, ColorId.circleButtonActive, ColorId.circleButtonActiveIcon);
      } else {
        // No filter - regular color
        filterTopicButton.init(R.drawable.baseline_tune_24, 56f, 4f, ColorId.circleButtonRegular, ColorId.circleButtonRegularIcon);
      }
    }

    if (topicIds.isEmpty()) {
      // Show all results
      messageSearchResults.clear();
      messageSearchResults.addAll(unfilteredMessageResults);
    } else {
      // Filter to only show messages from selected topics
      messageSearchResults.clear();
      for (TopicMessageSearchResult result : unfilteredMessageResults) {
        long topicId = result.topic.info.forumTopicId; // Cast int to long for Set<Long> contains check
        if (topicIds.contains(topicId)) {
          messageSearchResults.add(result);
        }
      }
    }

    // Auto-retry: if filtered results are empty but more messages available, load more
    if (messageSearchResults.isEmpty() && !topicIds.isEmpty() && canLoadMoreMessages && filterAutoRetryCount < MAX_AUTO_RETRY) {
      filterAutoRetryCount++;
      pendingFilterTopicIds = new java.util.HashSet<>(topicIds);

      // Show "searching deeper" message
      emptyView.setVisibility(View.VISIBLE);
      emptyView.showInfo(Lang.getString(R.string.LoadingTopics) + "...");

      // Load more pages
      loadMoreMessages();
      return;
    }

    // Clear pending filter
    pendingFilterTopicIds = null;

    adapter.setMessageSearchResults(messageSearchResults, currentSearchQuery);
    updateEmptyViewForMessageSearch();
  }

  private void showCreateTopicDialog () {
    openInputAlert(
      Lang.getString(R.string.NewTopic),
      Lang.getString(R.string.TopicNameHint),
      R.string.Done,
      R.string.Cancel,
      null,
      (inputView, result) -> {
        String name = result.trim();
        if (name.isEmpty()) {
          inputView.setInErrorState(true);
          return false;
        }
        if (name.length() > 128) {
          inputView.setInErrorState(true);
          return false;
        }
        createTopic(name);
        return true;
      },
      true
    );
  }

  private void createTopic (String name) {
    // Standard topic colors from Telegram
    int[] topicColors = {
      0x6FB9F0, // Blue
      0xFFD67E, // Yellow
      0xCB86DB, // Purple
      0x8EEE98, // Green
      0xFF93B2, // Pink
      0xFB6F5F  // Red
    };
    // Pick a random color
    int color = topicColors[(int) (Math.random() * topicColors.length)];

    TdApi.ForumTopicIcon icon = new TdApi.ForumTopicIcon(color, 0);

    tdlib.client().send(new TdApi.CreateForumTopic(chatId, name, false, icon), result -> {
      UI.post(() -> {
        if (result.getConstructor() == TdApi.ForumTopicInfo.CONSTRUCTOR) {
          // Reload topics to show the new one
          isLoading = false;
          loadTopics();
        } else if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
          UI.showError(result);
        }
      });
    });
  }

  // ChatListener (ForumTopicInfoListener) implementation
  @Override
  public void onForumTopicInfoChanged (TdApi.ForumTopicInfo info) {
    if (info.chatId != chatId) return;
    UI.post(() -> {
      if (isDestroyed() || topics == null) return;
      // A GetForumTopics response can emit this before the page itself. Updating
      // a title/icon must not invalidate the page's message and unread snapshot.
      for (int i = 0; i < topics.size(); i++) {
        if (topics.get(i).info.forumTopicId == info.forumTopicId) {
          topics.get(i).info = info;
          adapter.notifyTopicChanged(info.forumTopicId);
          break;
        }
      }
      if (allTopics != null) {
        int index = findTopic(allTopics, info.forumTopicId);
        if (index >= 0) allTopics.get(index).info = info;
      }
    });
  }

  // NotificationSettingsListener implementation

  @Override
  public void onNotificationSettingsChanged (long chatId, TdApi.ChatNotificationSettings settings) {
    // Parent chat's notification settings changed — topics using useDefaultMuteFor inherit from chat
    if (chatId != this.chatId || topics == null) return;
    UI.post(() -> {
      if (adapter != null) {
        adapter.notifyDataSetChanged();
      }
    });
  }

  @Override
  public void onNotificationSettingsChanged (TdApi.NotificationSettingsScope scope, TdApi.ScopeNotificationSettings settings) {
    // Scope-level notification settings changed — affects topics where both topic and chat use defaults
    if (topics == null) return;
    UI.post(() -> {
      if (adapter != null) {
        adapter.notifyDataSetChanged();
      }
    });
  }

  // Permission checks for topic actions
  private boolean canCreateOrDeletePrivateTopics () {
    TdApi.User user = tdlib.chatUser(chat);
    return user != null &&
      user.type.getConstructor() == TdApi.UserTypeBot.CONSTRUCTOR &&
      ((TdApi.UserTypeBot) user.type).allowsUsersToCreateTopics;
  }

  private boolean canCreateTopics () {
    if (tdlib.isPrivateChatWithTopics(chat)) {
      return canCreateOrDeletePrivateTopics();
    }

    TdApi.ChatMemberStatus status = tdlib.chatStatus(chatId);
    if (status == null) return false;

    switch (status.getConstructor()) {
      case TdApi.ChatMemberStatusCreator.CONSTRUCTOR:
        return true;
      case TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR:
        return ((TdApi.ChatMemberStatusAdministrator) status).rights.canManageTopics;
      case TdApi.ChatMemberStatusMember.CONSTRUCTOR:
      case TdApi.ChatMemberStatusRestricted.CONSTRUCTOR:
        // Check chat-level permissions
        return chat != null && chat.permissions != null && chat.permissions.canCreateTopics;
      default:
        return false;
    }
  }

  private boolean canDeleteTopics () {
    if (tdlib.isPrivateChatWithTopics(chat)) {
      return canCreateOrDeletePrivateTopics();
    }
    return canManageTopics();
  }

  private boolean canManageTopics () {
    // Private bot topic capability doesn't grant forum administration rights.
    if (tdlib.isPrivateChatWithTopics(chat)) {
      return false;
    }

    TdApi.ChatMemberStatus status = tdlib.chatStatus(chatId);
    if (status == null) return false;

    switch (status.getConstructor()) {
      case TdApi.ChatMemberStatusCreator.CONSTRUCTOR:
        return true;
      case TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR:
        return ((TdApi.ChatMemberStatusAdministrator) status).rights.canManageTopics;
      default:
        return false;
    }
  }

  // TdlibCache.SupergroupDataChangeListener implementation
  @Override
  public void onSupergroupUpdated (TdApi.Supergroup supergroup) {
    tdlib.ui().post(() -> {
      if (ChatId.toSupergroupId(chatId) == supergroup.id) {
        // Check if forum mode was disabled externally
        if (!supergroup.isForum && chat != null) {
          // Forum mode was disabled - navigate to regular chat view
          navigateBack();
          tdlib.ui().post(() -> {
            if (!isDestroyed()) {
              tdlib.ui().openChat(this, chat.id, new TdlibUi.ChatOpenParameters().keepStack());
            }
          });
        }
      }
    });
  }

  @Override
  public void onSupergroupFullUpdated (long supergroupId, TdApi.SupergroupFullInfo newSupergroupFull) {
    // Not used
  }

  // Message search result data class
  public static class TopicMessageSearchResult {
    public final TdApi.ForumTopic topic;
    public final TdApi.Message foundMessage;
    public final String highlightQuery;

    public TopicMessageSearchResult (TdApi.ForumTopic topic, TdApi.Message foundMessage, String query) {
      this.topic = topic;
      this.foundMessage = foundMessage;
      this.highlightQuery = query;
    }
  }

  // Inner adapter class
  private static class ForumTopicsAdapter extends RecyclerView.Adapter<ForumTopicViewHolder> {
    private final ForumTopicsController controller;
    private List<TdApi.ForumTopic> topics = new ArrayList<>();
    private List<TopicMessageSearchResult> messageSearchResults = new ArrayList<>();
    private boolean isMessageSearchMode = false;
    private String highlightQuery;

    ForumTopicsAdapter (ForumTopicsController controller) {
      this.controller = controller;
      setHasStableIds(true);
    }

    void setTopics (List<TdApi.ForumTopic> topics, @Nullable String highlightQuery) {
      setTopics(topics, highlightQuery, true);
    }

    void setTopics (List<TdApi.ForumTopic> topics, @Nullable String highlightQuery, boolean useDiff) {
      final boolean wasMessageSearchMode = isMessageSearchMode;
      final List<TdApi.ForumTopic> oldTopics = this.topics;
      final List<TdApi.ForumTopic> newTopics = new ArrayList<>(topics);
      int anchorTopicId = 0;
      int anchorOffset = 0;
      LinearLayoutManager layoutManager = controller.recyclerView != null ?
        (LinearLayoutManager) controller.recyclerView.getLayoutManager() : null;
      if (!wasMessageSearchMode && layoutManager != null) {
        int firstVisible = layoutManager.findFirstVisibleItemPosition();
        if (firstVisible >= 0 && firstVisible < oldTopics.size()) {
          View anchorView = layoutManager.findViewByPosition(firstVisible);
          if (anchorView != null && (firstVisible > 0 ||
              anchorView.getTop() < controller.recyclerView.getPaddingTop())) {
            anchorTopicId = oldTopics.get(firstVisible).info.forumTopicId;
            anchorOffset = anchorView.getTop() - controller.recyclerView.getPaddingTop();
          }
        }
      }
      DiffUtil.DiffResult diffResult = null;
      if (useDiff && !wasMessageSearchMode && !oldTopics.isEmpty()) {
        diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
          @Override
          public int getOldListSize () {
            return oldTopics.size();
          }

          @Override
          public int getNewListSize () {
            return newTopics.size();
          }

          @Override
          public boolean areItemsTheSame (int oldItemPosition, int newItemPosition) {
            return oldTopics.get(oldItemPosition).info.forumTopicId ==
              newTopics.get(newItemPosition).info.forumTopicId;
          }

          @Override
          public boolean areContentsTheSame (int oldItemPosition, int newItemPosition) {
            // ForumTopic is mutable and listener callbacks update it in place.
            // Always rebind a retained row, while DiffUtil still preserves its identity and movement.
            return false;
          }
        }, true);
      }
      this.topics = newTopics;
      this.highlightQuery = highlightQuery;
      this.isMessageSearchMode = false;
      if (diffResult != null) {
        diffResult.dispatchUpdatesTo(this);
      } else {
        notifyDataSetChanged();
      }
      if (anchorTopicId != 0 && layoutManager != null) {
        int anchorPosition = findTopic(newTopics, anchorTopicId);
        if (anchorPosition >= 0) {
          layoutManager.scrollToPositionWithOffset(anchorPosition, anchorOffset);
        }
      }
    }

    void setMessageSearchResults (List<TopicMessageSearchResult> results, @Nullable String highlightQuery) {
      this.messageSearchResults = results != null ? results : new ArrayList<>();
      this.highlightQuery = highlightQuery;
      this.isMessageSearchMode = true;
      notifyDataSetChanged();
    }

    boolean isShowingTopics () {
      return !isMessageSearchMode;
    }

    void notifyTopicChanged (int topicId) {
      if (isMessageSearchMode) return;
      int index = findTopic(topics, topicId);
      if (index >= 0) notifyItemChanged(index);
    }

    @NonNull
    @Override
    public ForumTopicViewHolder onCreateViewHolder (@NonNull ViewGroup parent, int viewType) {
      ForumTopicView view = new ForumTopicView(parent.getContext());
      view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ForumTopicView.getViewHeight(Settings.instance().getChatListMode())));
      view.setOnClickListener(controller);
      view.setOnLongClickListener(controller);
      controller.addThemeInvalidateListener(view);
      return new ForumTopicViewHolder(view);
    }

    @Override
    public void onBindViewHolder (@NonNull ForumTopicViewHolder holder, int position) {
      if (isMessageSearchMode) {
        TopicMessageSearchResult result = messageSearchResults.get(position);
        holder.bindMessageSearchResult(controller.tdlib, result);
      } else {
        TdApi.ForumTopic topic = topics.get(position);
        holder.bind(controller.tdlib, topic, highlightQuery);
      }
    }

    @Override
    public void onViewAttachedToWindow (@NonNull ForumTopicViewHolder holder) {
      if (holder.itemView instanceof ForumTopicView) {
        ((ForumTopicView) holder.itemView).attach();
      }
    }

    @Override
    public void onViewDetachedFromWindow (@NonNull ForumTopicViewHolder holder) {
      if (holder.itemView instanceof ForumTopicView) {
        ((ForumTopicView) holder.itemView).detach();
      }
    }

    @Override
    public void onViewRecycled (@NonNull ForumTopicViewHolder holder) {
      if (holder.itemView instanceof ForumTopicView) {
        ((ForumTopicView) holder.itemView).destroy();
      }
    }

    @Override
    public int getItemCount () {
      return isMessageSearchMode ? messageSearchResults.size() : topics.size();
    }

    @Override
    public long getItemId (int position) {
      return isMessageSearchMode ? messageSearchResults.get(position).foundMessage.id :
        topics.get(position).info.forumTopicId;
    }
  }

  private static class ForumTopicViewHolder extends RecyclerView.ViewHolder {
    ForumTopicViewHolder (@NonNull View itemView) {
      super(itemView);
    }

    void bind (Tdlib tdlib, TdApi.ForumTopic topic, @Nullable String highlightQuery) {
      if (itemView instanceof ForumTopicView) {
        ((ForumTopicView) itemView).setTopic(tdlib, topic, highlightQuery);
        itemView.setTag(topic);
      }
    }

    void bindMessageSearchResult (Tdlib tdlib, TopicMessageSearchResult result) {
      if (itemView instanceof ForumTopicView) {
        ((ForumTopicView) itemView).setMessageSearchResult(tdlib, result.topic, result.foundMessage, result.highlightQuery);
        itemView.setTag(result);
      }
    }
  }
}
