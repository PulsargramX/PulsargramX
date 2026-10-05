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
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.webapp.WebAppLaunchRequest;
import org.thunderdog.challegram.component.webapp.WebAppSession;
import org.thunderdog.challegram.component.user.UserView;
import org.thunderdog.challegram.data.TGUser;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.Menu;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;
import java.util.HashSet;

import org.thunderdog.challegram.data.TD;

/** Account-specific app discovery, also used by the attachment menu. */
public final class WebAppBrowserController extends RecyclerViewController<Void> implements View.OnClickListener, View.OnLongClickListener, Menu {
  private static final int CONTROLLER_ID = androidx.core.view.ViewCompat.generateViewId();
  private static final int APP_ID = androidx.core.view.ViewCompat.generateViewId();
  private static final int SESSION_ID = androidx.core.view.ViewCompat.generateViewId();
  private static final int MORE_ID = androidx.core.view.ViewCompat.generateViewId();
  private final MessagesController sourceChat;
  private final Runnable appsChanged = this::rebuild;
  private final ArrayList<Long> popular = new ArrayList<>();
  private final ArrayList<Long> recent = new ArrayList<>();
  private final ArrayList<Long> found = new ArrayList<>();
  private final HashSet<Long> requestedUsers = new HashSet<>();
  private SettingsAdapter adapter;
  private String query = "", nextOffset = "";
  private int searchGeneration;
  private boolean loading;

  public WebAppBrowserController (Context context, Tdlib tdlib, MessagesController sourceChat) {
    super(context, tdlib);
    this.sourceChat = sourceChat;
  }

  @Override public int getId () { return CONTROLLER_ID; }
  @Override public CharSequence getName () { return Lang.getString(R.string.WebAppBrowseApps); }
  @Override protected int getMenuId () { return R.id.menu_search; }
  @Override protected int getSearchMenuId () { return R.id.menu_clear; }

  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    if (id == R.id.menu_search) header.addSearchButton(menu, this, getHeaderIconColorId());
    else if (id == R.id.menu_clear) header.addClearButton(menu, this);
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id == R.id.menu_btn_search) openSearchMode();
    else if (id == R.id.menu_btn_clear) clearSearchInput();
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setUser (ListItem item, int position, UserView view, boolean isUpdate) {
        long botId = item.getId() == SESSION_ID ?
          ((WebAppSession) item.getData()).request.botUserId : item.getLongId();
        TdApi.User bot = tdlib.cache().user(botId);
        if (bot != null) {
          TGUser user = new TGUser(tdlib, bot);
          if (item.getData() instanceof WebAppSession) {
            user.setCustomStatus(Lang.getString(R.string.WebAppRuntimeRestore));
          } else {
            user.setCustomStatus(Lang.getString(R.string.WebAppOpenApp));
          }
          view.setUser(user);
        }
        view.setOnLongClickListener(WebAppBrowserController.this);
      }
    };
    recyclerView.setAdapter(adapter);
    tdlib.webApps().addListener(appsChanged);
    rebuild();
    loadPopular();
    tdlib.send(new TdApi.GetTopChats(new TdApi.TopChatCategoryWebAppBots(), 30), (chats, error) ->
      runOnUiThreadOptional(() -> {
        if (chats != null) {
          for (long chatId : chats.chatIds) {
            long userId = tdlib.chatUserId(chatId);
            if (userId != 0) recent.add(userId);
          }
          rebuild();
        }
      }));
  }

  private void loadPopular () {
    if (loading) return;
    loading = true;
    tdlib.send(new TdApi.GetGrossingWebAppBots(nextOffset, 30), (users, error) ->
      runOnUiThreadOptional(() -> {
        loading = false;
        if (users != null) {
          nextOffset = users.nextOffset;
          for (long id : users.userIds) if (!popular.contains(id)) popular.add(id);
        } else if (error != null) {
          UI.showError(error);
        }
        rebuild();
      }));
  }

  @Override
  protected void onSearchInputChanged (String input) {
    super.onSearchInputChanged(input);
    search(input);
  }

  @Override
  protected void onLeaveSearchMode () {
    super.onLeaveSearchMode();
    search("");
  }

  private void search (String input) {
    query = input.trim();
    int generation = ++searchGeneration;
    found.clear();
    rebuild();
    if (query.isEmpty()) return;
    tdlib.send(new TdApi.SearchPublicChats(query, null), (chats, error) -> runOnUiThreadOptional(() -> {
      if (generation != searchGeneration) return;
      if (chats != null) {
        for (long id : chats.chatIds) {
          long userId = tdlib.chatUserId(id);
          if (hasMainApp(userId)) found.add(userId);
        }
      } else if (error != null) {
        UI.showError(error);
      }
      rebuild();
    }));
  }

  private boolean hasMainApp (long id) {
    TdApi.User user = tdlib.cache().user(id);
    return user != null && user.type instanceof TdApi.UserTypeBot &&
      ((TdApi.UserTypeBot) user.type).hasMainWebApp;
  }

  private ListItem appItem (int id, long botId, CharSequence name, Object data) {
    boolean cached = tdlib.cache().user(botId) != null;
    if (!cached && requestedUsers.add(botId)) {
      tdlib.send(new TdApi.GetUser(botId), (user, error) -> runOnUiThreadOptional(() -> {
        if (user != null) rebuild();
      }));
    }
    return new ListItem(cached ? ListItem.TYPE_USER_SMALL : ListItem.TYPE_SETTING, id,
      cached ? 0 : R.drawable.baseline_apps_24, name, false).setLongId(botId).setData(data);
  }

  private void addUsers (ArrayList<ListItem> items, ArrayList<Long> users, int title,
                         HashSet<Long> seen) {
    boolean header = false;
    for (long id : users) {
      if (!hasMainApp(id) || !seen.add(id)) continue;
      if (!header) {
        items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, title));
        header = true;
      }
      TdApi.User user = tdlib.cache().user(id);
      items.add(appItem(APP_ID, id, TD.getUserName(user), null));
    }
  }

  private void rebuild () {
    if (adapter == null || isDestroyed()) return;
    ArrayList<ListItem> items = new ArrayList<>();
    HashSet<Long> seen = new HashSet<>();
    if (query.isEmpty()) {
      boolean sessionsHeader = false;
      for (WebAppSession session : tdlib.webApps().getOpenSessions()) {
        if (session.isClosed()) continue;
        if (!sessionsHeader) {
          items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.WebAppOpenApps));
          sessionsHeader = true;
        }
        items.add(appItem(SESSION_ID, session.request.botUserId,
          TD.getUserName(tdlib.cache().user(session.request.botUserId)), session));
      }
      boolean header = false;
      for (TdApi.AttachmentMenuBot bot : tdlib.webApps().getAttachmentMenuBots()) {
        if (!bot.isAdded || !(sourceChat != null ? bot.showInAttachmentMenu : bot.showInSideMenu)) continue;
        if (!supportsChat(bot)) continue;
        if (!header) {
          items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.WebAppInstalledApps));
          header = true;
        }
        seen.add(bot.botUserId);
        items.add(appItem(APP_ID, bot.botUserId, bot.name, bot));
      }
      addUsers(items, recent, R.string.Recent, seen);
      addUsers(items, popular, R.string.WebAppPopularApps, seen);
      if (!nextOffset.isEmpty()) {
        items.add(new ListItem(ListItem.TYPE_SETTING, MORE_ID, R.drawable.baseline_more_horiz_24,
          R.string.RichMessageShowMore));
      }
    } else {
      addUsers(items, found, R.string.Search, seen);
    }
    if (items.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0,
        loading ? R.string.LoadingInformation : R.string.WebAppNoApps));
    } else {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.WebAppManageAppsHint));
    }
    adapter.setItems(items, false);
  }

  private boolean supportsChat (TdApi.AttachmentMenuBot bot) {
    if (sourceChat == null) return true;
    long id = sourceChat.getChatId();
    if (tdlib.isSelfChat(id)) return bot.supportsSelfChat;
    if (tdlib.isBotChat(id)) return bot.supportsBotChats;
    if (tdlib.isUserChat(id)) return bot.supportsUserChats;
    if (tdlib.isChannel(id)) return bot.supportsChannelChats;
    return bot.supportsGroupChats;
  }

  @Override
  public void onClick (View view) {
    if (view.getId() == SESSION_ID) {
      tdlib.webApps().restore(this, (WebAppSession) ((ListItem) view.getTag()).getData());
      return;
    }
    if (view.getId() == MORE_ID) {
      loadPopular();
      return;
    }
    if (view.getId() != APP_ID) return;
    ListItem item = (ListItem) view.getTag();
    WebAppLaunchRequest.Source source = item.getData() instanceof TdApi.AttachmentMenuBot ?
      (sourceChat != null ? WebAppLaunchRequest.Source.ATTACHMENT_MENU : WebAppLaunchRequest.Source.SIDE_MENU) :
      WebAppLaunchRequest.Source.MAIN;
    WebAppLaunchRequest request = new WebAppLaunchRequest(source, item.getLongId());
    if (sourceChat != null && !sourceChat.isDestroyed()) {
      request.chatId = sourceChat.getChatId();
      request.topicId = sourceChat.getMessageTopicId();
      if (sourceChat.getCurrentReplyId() != null) request.replyTo = sourceChat.getCurrentReplyId().toInputMessageReply();
    }
    tdlib.webApps().open(this, request);
  }

  @Override
  public boolean onLongClick (View view) {
    if (view.getId() != APP_ID && view.getId() != SESSION_ID) return false;
    ListItem item = (ListItem) view.getTag();
    long botId = view.getId() == SESSION_ID ?
      ((WebAppSession) item.getData()).request.botUserId : item.getLongId();
    boolean installed = false;
    for (TdApi.AttachmentMenuBot bot : tdlib.webApps().getAttachmentMenuBots()) {
      if (bot.botUserId == botId && bot.isAdded) {
        installed = true;
        break;
      }
    }
    int[] ids = installed ? new int[] {1, 2, 3} : new int[] {1, 2};
    String[] labels = installed ? new String[] {
      Lang.getString(R.string.WebAppCloseSessions), Lang.getString(R.string.WebAppClearLocalData),
      Lang.getString(R.string.WebAppRemoveApp)
    } : new String[] {
      Lang.getString(R.string.WebAppCloseSessions), Lang.getString(R.string.WebAppClearLocalData)
    };
    showOptions(TD.getUserName(tdlib.cache().user(botId)), ids, labels, null, null, (v, choice) -> {
      if (choice == 1) {
        tdlib.webApps().closeBotSessions(botId);
      } else {
        boolean removeFromMenu = choice == 3;
        showOptions(Lang.getString(removeFromMenu ? R.string.WebAppRemoveAppConfirm :
            R.string.WebAppClearLocalDataConfirm),
          new int[] {R.id.btn_done, R.id.btn_cancel},
          new String[] {Lang.getString(removeFromMenu ? R.string.WebAppRemoveApp :
            R.string.WebAppClearLocalData), Lang.getString(R.string.Cancel)},
          new int[] {OptionColor.RED, OptionColor.NORMAL}, null, (button, action) -> {
            if (action == R.id.btn_done) {
              tdlib.webApps().forgetBot(botId, removeFromMenu, this::rebuild);
            }
            return true;
          });
      }
      return true;
    });
    return true;
  }

  @Override
  public void destroy () {
    tdlib.webApps().removeListener(appsChanged);
    super.destroy();
  }
}
