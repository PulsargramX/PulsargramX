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
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.dialogs.SearchManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.data.TGFoundChat;
import org.thunderdog.challegram.emoji.Emoji;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.navigation.ViewPagerController;
import org.thunderdog.challegram.navigation.ViewPagerHeaderViewCompact;
import org.thunderdog.challegram.navigation.ViewPagerTopView;
import org.thunderdog.challegram.telegram.ChatFolderStyle;
import org.thunderdog.challegram.telegram.ChatFoldersListener;
import org.thunderdog.challegram.telegram.CounterChangeListener;
import org.thunderdog.challegram.telegram.GlobalCountersListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibCounter;
import org.thunderdog.challegram.telegram.TdlibSettingsManager;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.Counter;
import org.thunderdog.challegram.widget.ViewPager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.BitwiseUtils;
import tgx.td.ChatPosition;
import tgx.td.Td;

public class ChatPickerController extends ViewPagerController<ChatsController.Arguments>
  implements ChatFoldersListener, CounterChangeListener, GlobalCountersListener,
  TdlibSettingsManager.ChatListPositionListener, Settings.ChatFolderSettingsListener {
  private final List<TdApi.ChatList> chatLists = new ArrayList<>();
  private List<ViewPagerTopView.Item> sections = new ArrayList<>();

  public ChatPickerController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public void setArguments (ChatsController.Arguments args) {
    if (args.pickerDelegate == null) {
      throw new IllegalArgumentException("A chat picker requires a delegate");
    }
    super.setArguments(args);
    updateFolders(tdlib.chatFolders(), tdlib.mainChatListPosition());
  }

  private void updateFolders (TdApi.ChatFolderInfo[] folders, int mainPosition) {
    chatLists.clear();
    sections = new ArrayList<>();
    List<TdApi.ChatFolderInfo> ordered = new ArrayList<>(Arrays.asList(folders));
    ordered.add(Math.max(0, Math.min(mainPosition, ordered.size())), null);
    @ChatFolderStyle int style = tdlib.settings().chatFolderStyle();
    for (TdApi.ChatFolderInfo folder : ordered) {
      if (folder != null && !tdlib.settings().isChatFolderEnabled(folder.id)) {
        continue;
      }
      TdApi.ChatList list = folder != null ?
        new TdApi.ChatListFolder(folder.id) : ChatPosition.CHAT_LIST_MAIN;
      boolean firstMainTab = folder == null && chatLists.isEmpty();
      CharSequence label = folder != null ? Emoji.instance().replaceEmoji(TD.toCharSequence(folder.name)) :
        Lang.getString(R.string.CategoryMain);
      if (!ChatFolderTab.hasLabel(style, firstMainTab)) {
        label = null;
      }
      int icon = folder != null ? TD.findFolderIcon(folder.icon, R.drawable.baseline_folder_24) :
        R.drawable.baseline_forum_24;
      chatLists.add(list);
      long itemId = getPagerItemId(chatLists.size() - 1);
      Counter counter = ChatFolderTab.buildCounter(() -> false, (changed, sizeChanged) -> {
        if (headerCell != null) {
          int position = getPagerItemPosition(itemId);
          if (position != NO_POSITION) {
            ViewPagerTopView top = headerCell.getTopView();
            if (sizeChanged) {
              top.requestItemLayoutAt(position);
            }
            top.invalidate();
          }
        }
      });
      MainController.updateCounter(tdlib, list, counter, tdlib.getCounter(list), false);
      sections.add(ChatFolderTab.build(label, icon, style, firstMainTab, counter));
    }
  }

  private void configureTabs () {
    if (headerCell != null) {
      ViewPagerHeaderViewCompact header = (ViewPagerHeaderViewCompact) headerCell.getView();
      ChatFolderTab.configure(header, tdlib.settings().chatFolderStyle(), true, false);
      // The folder row has its own line below the title and toolbar buttons.
      ViewGroup.MarginLayoutParams params =
        (ViewGroup.MarginLayoutParams) header.getRecyclerView().getLayoutParams();
      if (params.leftMargin != 0 || params.rightMargin != 0) {
        params.leftMargin = params.rightMargin = 0;
        Views.updateLayoutParams(header.getRecyclerView());
      }
    }
  }

  @Override
  public int getId () {
    return R.id.controller_chatPicker;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(getArgumentsStrict().pickerDelegate.getTitleStringRes());
  }

  @Override
  protected int getBackButton () {
    return BackHeaderButton.TYPE_BACK;
  }

  @Override
  protected int getTitleStyle () {
    return TITLE_STYLE_COMPACT_BIG;
  }

  @Override
  protected int getMenuId () {
    return R.id.menu_search;
  }

  @Override
  protected int getMenuButtonsWidth () {
    return Screen.dp(49f);
  }

  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    if (id == R.id.menu_search) {
      header.addSearchButton(menu, this);
    } else if (id == R.id.menu_clear) {
      header.addClearButton(menu, getSearchHeaderIconColorId(), getSearchBackButtonResource());
    }
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id == R.id.menu_btn_search) {
      openSearchMode();
    } else if (id == R.id.menu_btn_clear) {
      clearSearchInput();
    }
  }

  @Override
  public boolean supportsBottomInset () {
    return true;
  }

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, ViewPager pager) {
    generateChatSearchView(contentView);
    configureTabs();
    tdlib.listeners().subscribeToChatFoldersUpdates(this);
    tdlib.listeners().addTotalChatCounterListener(this);
    tdlib.context().global().addCountersListener(this);
    tdlib.settings().addChatListPositionListener(this);
    Settings.instance().addChatFolderSettingsListener(this);
    int mainPosition = getPagerItemPosition(0);
    setCurrentPagerPosition(mainPosition, false);
    prepareControllerForPosition(mainPosition, this::executeScheduledAnimation);
  }

  @Override
  protected int getPagerItemCount () {
    return chatLists.size();
  }

  @Override
  protected long getPagerItemId (int position) {
    TdApi.ChatList list = chatLists.get(position);
    return list instanceof TdApi.ChatListFolder ? ((TdApi.ChatListFolder) list).chatFolderId : 0;
  }

  @Override
  protected CharSequence[] getPagerSections () {
    return null;
  }

  @Override
  protected List<ViewPagerTopView.Item> getPagerSectionItems () {
    return sections;
  }

  @Override
  protected ViewController<?> onCreatePagerItemForPosition (Context context, int position) {
    ChatsController.Arguments args = getArgumentsStrict();
    ChatsController controller = new ChatsController(context, tdlib);
    controller.setArguments(new ChatsController.Arguments(args.filter, args.pickerDelegate)
      .setChatList(chatLists.get(position)));
    return controller;
  }

  @Override
  protected int getChatSearchFlags () {
    return SearchManager.FLAG_NEED_TOP_CHATS | SearchManager.FLAG_ONLY_WRITABLE;
  }

  @Override
  protected boolean filterChatSearchResult (TdApi.Chat chat) {
    ChatsController.Arguments args = getArgumentsStrict();
    TdApi.ChatList list = chatLists.get(getCurrentPagerItemPosition());
    return chat != null && (args.filter == null || args.filter.accept(chat)) &&
      (TD.isChatListMain(list) || ChatPosition.findPosition(chat, list) != null);
  }

  @Override
  protected boolean onFoundChatClick (View view, TGFoundChat chat) {
    ChatsController controller = (ChatsController)
      getPreparedControllerForPosition(getCurrentPagerItemPosition());
    return controller.onFoundChatClick(view, chat);
  }

  @Override
  protected View getSearchAntagonistView () {
    return getViewPager();
  }

  @Override
  protected void onEnterSearchMode () {
    super.onEnterSearchMode();
    getViewPager().setPagingEnabled(false);
  }

  @Override
  protected void onLeaveSearchMode () {
    super.onLeaveSearchMode();
    getViewPager().setPagingEnabled(true);
  }

  @Override
  public void onChatFoldersChanged (TdApi.ChatFolderInfo[] folders, int mainPosition) {
    runOnUiThreadOptional(() -> {
      long selectedFolder = getCurrentPagerItemId();
      updateFolders(folders, mainPosition);
      notifyPagerItemPositionsChanged();
      configureTabs();
      int position = getPagerItemPosition(selectedFolder);
      setCurrentPagerPosition(position != NO_POSITION ? position : getPagerItemPosition(0), false);
    });
  }

  private void updateCounters () {
    for (int i = 0; i < sections.size(); i++) {
      TdApi.ChatList list = chatLists.get(i);
      MainController.updateCounter(tdlib, list, sections.get(i).counter, tdlib.getCounter(list),
        isFocused());
    }
  }

  private void onCounterChanged (TdApi.ChatList changed, boolean messages) {
    runOnUiThreadOptional(() -> {
      int flags = tdlib.settings().getChatFolderBadgeFlags();
      if (messages != BitwiseUtils.hasFlag(flags, Settings.BADGE_FLAG_MESSAGES)) {
        return;
      }
      for (int i = 0; i < chatLists.size(); i++) {
        TdApi.ChatList list = chatLists.get(i);
        if (Td.equalsTo(list, changed) || (TD.isChatListMain(list) &&
            changed instanceof TdApi.ChatListArchive &&
            BitwiseUtils.hasFlag(flags, Settings.BADGE_FLAG_ARCHIVED))) {
          MainController.updateCounter(tdlib, list, sections.get(i).counter, tdlib.getCounter(list),
            isFocused());
        }
      }
    });
  }

  @Override
  public void onChatCounterChanged (@NonNull TdApi.ChatList chatList, TdlibCounter counter,
    boolean availabilityChanged, int totalCount, int unreadCount, int unreadUnmutedCount) {
    onCounterChanged(chatList, false);
  }

  @Override
  public void onMessageCounterChanged (@NonNull TdApi.ChatList chatList, TdlibCounter counter,
    int unreadCount, int unreadUnmutedCount) {
    onCounterChanged(chatList, true);
  }

  @Override
  public void onBadgeFlagsChanged (Tdlib source, int flags) {
    runOnUiThreadOptional(this::updateCounters);
  }

  @Override
  public void onBadgeSettingsChanged () {
    runOnUiThreadOptional(this::updateCounters);
  }

  @Override
  public void onChatFolderStyleChanged (Tdlib source, int style) {
    onChatFolderStyleChanged(style);
  }

  @Override
  public void onChatFolderStyleChanged (int style) {
    onChatFoldersChanged(tdlib.chatFolders(), tdlib.mainChatListPosition());
  }

  @Override
  public void onChatListStateChanged (Tdlib source, TdApi.ChatList list, boolean enabled) {
    onChatFoldersChanged(tdlib.chatFolders(), tdlib.mainChatListPosition());
  }

  @Override
  public void destroy () {
    tdlib.listeners().unsubscribeFromChatFoldersUpdates(this);
    tdlib.listeners().removeTotalChatCounterListener(this);
    tdlib.context().global().removeCountersListener(this);
    tdlib.settings().removeChatListPositionListener(this);
    Settings.instance().removeChatFolderSettingsListener(this);
    super.destroy();
  }
}
