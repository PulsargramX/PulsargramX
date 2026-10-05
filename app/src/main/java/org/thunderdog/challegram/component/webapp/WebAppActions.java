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
package org.thunderdog.challegram.component.webapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.MessageListener;
import org.thunderdog.challegram.telegram.Tdlib;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Account-bound native operations requested by a Mini App document. */
public final class WebAppActions {
  public interface Host {
    Context context ();
    Activity activity ();
    Tdlib tdlib ();
    WebAppSession session ();
    ViewController<?> controller ();
    WebView webView ();
    boolean isAlive ();
    boolean hasRecentUserGesture ();
    default void onUserGesture () { }
    void emit (String event, JSONObject data);
    void close (boolean force);
    void openExternalUrl (String url);
    void runOnUiThread (Runnable runnable);
  }

  final Host host;
  private boolean destroyed;
  private boolean standalone;
  private final Set<AlertDialog> dialogs = new LinkedHashSet<>();
  private final Set<String> pending = new LinkedHashSet<>();
  private final Map<String, Consumer<Boolean>> sentMessages = new HashMap<>();
  private final Set<Long> observedChats = new LinkedHashSet<>();
  private final WebAppPayments payments;
  private final WebAppStoryComposer stories;
  private final WebAppDeviceAccess device;

  public WebAppActions (Host host) {
    this.host = host;
    payments = new WebAppPayments(this);
    stories = new WebAppStoryComposer(this);
    device = new WebAppDeviceAccess(this);
  }

  /** Open checkout from a native invoice link. A standalone host may return a null session. */
  public static WebAppActions openInvoice (Host host, String slug) {
    return openInvoice(host, new TdApi.InputInvoiceName(slug));
  }

  public static WebAppActions openInvoice (Host host, TdApi.InputInvoice invoice) {
    WebAppActions actions = new WebAppActions(host);
    actions.standalone = true;
    actions.payments.open(invoice);
    return actions;
  }

  public static void clearBotData (Context context, Tdlib tdlib, long botId) {
    WebAppDeviceAccess.clearBotData(context, tdlib, botId);
  }

  public boolean handle (String event, JSONObject data) {
    switch (event) {
      case "web_app_open_invoice": payments.open(data.optString("slug")); return true;
      case "web_app_request_write_access": requestWriteAccess(); return true;
      case "web_app_request_phone": requestPhone(); return true;
      case "web_app_request_emoji_status_access": requestEmojiAccess(); return true;
      case "web_app_set_emoji_status": setEmojiStatus(data); return true;
      case "web_app_send_prepared_message": sharePrepared(data.optString("id")); return true;
      case "web_app_request_chat": requestPeer(data.optString("req_id")); return true;
      case "web_app_share_to_story": if (host.hasRecentUserGesture()) stories.open(data); return true;
      case "web_app_invoke_custom_method": customMethod(data); return true;
      case "web_app_switch_inline_query": switchInline(data); return true;
      default: return device.handle(event, data);
    }
  }

  boolean alive () { return !destroyed && host.isAlive(); }
  long botId () { return host.session() == null ? 0 : host.session().request.botUserId; }
  String botName () {
    TdApi.User user = host.tdlib().cache().user(botId());
    return user != null ? (user.firstName + " " + user.lastName).trim() : Long.toString(botId());
  }
  static JSONObject object (Object... pairs) {
    JSONObject result = new JSONObject();
    try {
      for (int i = 0; i + 1 < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    } catch (org.json.JSONException ignored) { }
    return result;
  }
  void emit (String event, JSONObject data) {
    if (alive()) host.emit(event, data);
    if (standalone && "invoice_closed".equals(event)) destroy();
  }
  void send (TdApi.Function<?> function, Consumer<TdApi.Object> result) {
    if (!alive()) return;
    host.tdlib().client().send(function, value -> host.runOnUiThread(() -> {
      if (alive()) result.accept(value);
    }));
  }
  void error (TdApi.Object value) {
    if (alive()) Toast.makeText(host.context(), value instanceof TdApi.Error ?
      ((TdApi.Error) value).message : host.context().getString(R.string.WebAppActionFailed), Toast.LENGTH_LONG).show();
  }
  AlertDialog show (AlertDialog.Builder builder, Runnable cancel) {
    AlertDialog dialog = builder.create();
    dialogs.add(dialog);
    dialog.setOnCancelListener(d -> { if (alive() && cancel != null) cancel.run(); });
    dialog.setOnDismissListener(d -> dialogs.remove(dialog));
    if (alive()) dialog.show();
    return dialog;
  }
  void confirm (int title, String message, int positive, Runnable accept, Runnable cancel) {
    show(new AlertDialog.Builder(host.activity()).setTitle(title).setMessage(message)
      .setPositiveButton(positive, (d, which) -> { if (alive()) accept.run(); })
      .setNegativeButton(android.R.string.cancel, (d, which) -> { if (alive()) cancel.run(); }), cancel);
  }
  private boolean begin (String key) { return begin(key, true); }
  private boolean begin (String key, boolean requireGesture) {
    return alive() && (!requireGesture || host.hasRecentUserGesture()) && pending.add(key);
  }
  private void finishStatus (String key, String event, String status) {
    pending.remove(key);
    emit(event, object("status", status));
  }

  private void requestWriteAccess () {
    if (!begin("write", false)) { emit("write_access_requested", object("status", "cancelled")); return; }
    send(new TdApi.CanBotSendMessages(botId()), result -> {
      if (result instanceof TdApi.Ok) {
        finishStatus("write", "write_access_requested", "allowed");
      } else {
        Runnable cancel = () -> finishStatus("write", "write_access_requested", "cancelled");
        confirm(R.string.WebAppWriteAccessTitle, host.context().getString(R.string.WebAppWriteAccessText, botName()),
          R.string.WebAppAllow, () -> send(new TdApi.AllowBotToSendMessages(botId()), allowed ->
            finishStatus("write", "write_access_requested", allowed instanceof TdApi.Ok ? "allowed" : "cancelled")), cancel);
      }
    });
  }

  private void requestPhone () {
    if (!begin("phone", false)) { emit("phone_requested", object("status", "cancelled")); return; }
    Runnable cancel = () -> finishStatus("phone", "phone_requested", "cancelled");
    confirm(R.string.WebAppShareContactTitle, host.context().getString(R.string.WebAppShareContactText, botName()),
      R.string.WebAppShare, () -> send(new TdApi.CreatePrivateChat(botId(), false), result -> {
        TdApi.User me = host.tdlib().myUser();
        if (!(result instanceof TdApi.Chat) || me == null || me.phoneNumber.isEmpty()) { cancel.run(); return; }
        TdApi.Contact contact = new TdApi.Contact(me.phoneNumber, me.firstName, me.lastName, "", me.id);
        TdApi.Chat chat = (TdApi.Chat) result;
        Runnable share = () -> sendMessage(new TdApi.SendMessage(chat.id, null, null, null, null,
          new TdApi.InputMessageContact(contact)), chat.id,
          success -> finishStatus("phone", "phone_requested", success ? "sent" : "cancelled"));
        if (chat.blockList instanceof TdApi.BlockListMain) {
          confirm(R.string.WebAppShareContactTitle, host.context().getString(R.string.QUnblockX, botName()), R.string.Unblock,
            () -> send(new TdApi.SetMessageSenderBlockList(new TdApi.MessageSenderUser(botId()), null), unblocked -> {
              if (unblocked instanceof TdApi.Ok) share.run(); else cancel.run();
            }), cancel);
        } else share.run();
      }), cancel);
  }

  private void requestEmojiAccess () {
    if (!begin("emoji_access")) { emit("emoji_status_access_requested", object("status", "cancelled")); return; }
    Runnable cancel = () -> finishStatus("emoji_access", "emoji_status_access_requested", "cancelled");
    send(new TdApi.GetUserFullInfo(botId()), result -> {
      if (result instanceof TdApi.UserFullInfo && ((TdApi.UserFullInfo) result).botInfo != null &&
          ((TdApi.UserFullInfo) result).botInfo.canManageEmojiStatus) {
        finishStatus("emoji_access", "emoji_status_access_requested", "allowed"); return;
      }
      confirm(R.string.WebAppEmojiAccessTitle, host.context().getString(R.string.WebAppEmojiAccessText, botName()),
        R.string.WebAppAllow, () -> send(new TdApi.ToggleBotCanManageEmojiStatus(botId(), true), allowed ->
          finishStatus("emoji_access", "emoji_status_access_requested", allowed instanceof TdApi.Ok ? "allowed" : "cancelled")), cancel);
    });
  }

  private void setEmojiStatus (JSONObject data) {
    if (!begin("emoji")) { emit("emoji_status_failed", object("error", "USER_DECLINED")); return; }
    long emojiId;
    try { emojiId = Long.parseLong(data.optString("custom_emoji_id")); } catch (NumberFormatException ignored) { emojiId = 0; }
    long duration = data.optLong("duration", 0);
    if (emojiId == 0 || duration < 0 || duration > Integer.MAX_VALUE - System.currentTimeMillis() / 1000) {
      pending.remove("emoji"); emit("emoji_status_failed", object("error", "SUGGESTED_EMOJI_INVALID")); return;
    }
    TdApi.User me = host.tdlib().myUser();
    if (me == null || !me.isPremium) {
      pending.remove("emoji"); emit("emoji_status_failed", object("error", "PREMIUM_ACCOUNT_REQUIRED")); return;
    }
    final long id = emojiId;
    final int expiration = duration == 0 ? 0 : (int) (System.currentTimeMillis() / 1000 + duration);
    Runnable cancel = () -> { pending.remove("emoji"); emit("emoji_status_failed", object("error", "USER_DECLINED")); };
    send(new TdApi.GetCustomEmojiStickers(new long[] {id}), result -> {
      if (!(result instanceof TdApi.Stickers) || ((TdApi.Stickers) result).stickers.length == 0) {
        pending.remove("emoji"); emit("emoji_status_failed", object("error", "SUGGESTED_EMOJI_INVALID")); return;
      }
      String emoji = ((TdApi.Stickers) result).stickers[0].emoji;
      confirm(R.string.WebAppEmojiStatusTitle, host.context().getString(R.string.WebAppEmojiStatusText, botName(), emoji,
        duration == 0 ? host.context().getString(R.string.WebAppNoExpiry) : host.context().getString(R.string.WebAppDurationSeconds, duration)),
        R.string.WebAppSet, () -> send(new TdApi.SetEmojiStatus(new TdApi.EmojiStatus(new TdApi.EmojiStatusTypeCustomEmoji(id), expiration)), response -> {
          pending.remove("emoji");
          if (response instanceof TdApi.Ok) emit("emoji_status_set", new JSONObject());
          else emit("emoji_status_failed", object("error", "SERVER_ERROR"));
        }), cancel);
    });
  }

  private void customMethod (JSONObject data) {
    String requestId = data.optString("req_id");
    String method = data.optString("method");
    JSONObject parameters = data.optJSONObject("params");
    if (requestId.isEmpty() || method.isEmpty() || parameters == null) {
      emit("custom_method_invoked", object("req_id", requestId, "error", "INVALID_REQUEST")); return;
    }
    send(new TdApi.SendWebAppCustomRequest(botId(), method, parameters.toString()), result -> {
      if (result instanceof TdApi.CustomRequestResult) {
        try {
          Object parsed = new org.json.JSONTokener(((TdApi.CustomRequestResult) result).result).nextValue();
          emit("custom_method_invoked", object("req_id", requestId, "result", parsed));
        } catch (org.json.JSONException ignored) { emit("custom_method_invoked", object("req_id", requestId, "error", "INVALID_RESPONSE")); }
      } else emit("custom_method_invoked", object("req_id", requestId, "error", result instanceof TdApi.Error ? ((TdApi.Error) result).message : "UNKNOWN_ERROR"));
    });
  }

  private void switchInline (JSONObject data) {
    if (!host.hasRecentUserGesture()) return;
    TdApi.User bot = host.tdlib().cache().user(botId());
    if (bot == null || !(bot.type instanceof TdApi.UserTypeBot) || !((TdApi.UserTypeBot) bot.type).isInline) return;
    String username = tgx.td.Td.primaryUsername(bot);
    if (username == null || username.isEmpty()) return;
    String query = data.optString("query");
    org.json.JSONArray requested = data.optJSONArray("chat_types");
    Consumer<long[]> open = ids -> {
      if (!alive()) return;
      host.tdlib().ui().openChat(host.controller(), ids[0],
        new org.thunderdog.challegram.telegram.TdlibUi.ChatOpenParameters()
          .shareItem(new org.thunderdog.challegram.data.TGSwitchInline(username, query)));
    };
    if ((requested == null || requested.length() == 0) && host.session().request.chatId != 0) {
      open.accept(new long[] {host.session().request.chatId}); return;
    }
    TdApi.TargetChatTypes types = new TdApi.TargetChatTypes(true, true, true, true);
    if (requested != null && requested.length() > 0) {
      types = new TdApi.TargetChatTypes(false, false, false, false);
      for (int i = 0; i < requested.length(); i++) {
        switch (requested.optString(i)) {
          case "users": types.allowUserChats = true; break;
          case "bots": types.allowBotChats = true; break;
          case "groups": types.allowGroupChats = true; break;
          case "channels": types.allowChannelChats = true; break;
        }
      }
    }
    final TdApi.TargetChatTypes allowed = types;
    chooseChats(R.string.WebAppChooseChatTitle, chat -> allowedChat(chat, allowed) && (host.tdlib().chat(chat.id) == null || host.tdlib().canSendBasicMessage(chat)), false, 1, open, () -> { });
  }

  private void sharePrepared (String id) {
    if (!begin("prepared")) { emit("prepared_message_failed", object("error", "USER_DECLINED")); return; }
    Consumer<String> failed = code -> { pending.remove("prepared"); emit("prepared_message_failed", object("error", code)); };
    if (id.isEmpty()) { failed.accept("MESSAGE_EXPIRED"); return; }
    send(new TdApi.GetPreparedInlineMessage(botId(), id), response -> {
      if (!(response instanceof TdApi.PreparedInlineMessage)) { failed.accept("MESSAGE_EXPIRED"); return; }
      TdApi.PreparedInlineMessage message = (TdApi.PreparedInlineMessage) response;
      String resultId = inlineResultId(message.result);
      if (resultId == null) { failed.accept("MESSAGE_SEND_FAILED"); return; }
      chooseChats(R.string.WebAppShareMessageTitle, chat -> allowedChat(chat, message.chatTypes) && (host.tdlib().chat(chat.id) == null || host.tdlib().canSendBasicMessage(chat)), false, 1,
        chosen -> {
          long chatId = chosen[0];
          org.thunderdog.challegram.component.inline.CustomResultView preview =
            new org.thunderdog.challegram.component.inline.CustomResultView(host.activity());
          preview.setInlineResult(org.thunderdog.challegram.data.InlineResult.valueOf(
            host.controller().context(), host.tdlib(), "", message.result, null));
          LinearLayout container = column();
          container.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
          AlertDialog dialog = show(new AlertDialog.Builder(host.activity()).setTitle(R.string.WebAppShareMessageTitle)
            .setMessage(host.context().getString(R.string.WebAppShareMessageText, botName(), host.tdlib().chatTitle(chatId)))
            .setView(container).setPositiveButton(R.string.WebAppSend, (d, w) ->
              sendMessage(new TdApi.SendInlineQueryResultMessage(chatId, null, null, null,
                message.inlineQueryId, resultId, false), chatId, success -> {
                  if (success) { pending.remove("prepared"); emit("prepared_message_sent", new JSONObject()); }
                  else failed.accept("MESSAGE_SEND_FAILED");
                }))
            .setNegativeButton(android.R.string.cancel, (d, w) -> failed.accept("USER_DECLINED")), () -> failed.accept("USER_DECLINED"));
          dialog.setOnDismissListener(d -> { dialogs.remove(dialog); preview.performDestroy(); });
        }, () -> failed.accept("USER_DECLINED"));
    });
  }

  private boolean allowedChat (TdApi.Chat chat, TdApi.TargetChatTypes types) {
    if (chat.type instanceof TdApi.ChatTypePrivate) {
      TdApi.User user = host.tdlib().cache().user(((TdApi.ChatTypePrivate) chat.type).userId);
      return user != null && (user.type instanceof TdApi.UserTypeBot ? types.allowBotChats : types.allowUserChats);
    }
    if (chat.type instanceof TdApi.ChatTypeBasicGroup) return types.allowGroupChats;
    if (chat.type instanceof TdApi.ChatTypeSupergroup)
      return ((TdApi.ChatTypeSupergroup) chat.type).isChannel ? types.allowChannelChats : types.allowGroupChats;
    return false;
  }

  private void requestPeer (String id) {
    if (id.isEmpty()) { emit("requested_chat_failed", object("req_id", id)); return; }
    String key = "peer:" + id;
    if (!begin(key)) { emit("requested_chat_failed", object("req_id", id)); return; }
    Runnable fail = () -> { pending.remove(key); emit("requested_chat_failed", object("req_id", id)); };
    Consumer<TdApi.Object> completed = result -> {
      pending.remove(key);
      emit(result instanceof TdApi.Ok ? "requested_chat_sent" : "requested_chat_failed", object("req_id", id));
      if (result instanceof TdApi.Error) error(result);
    };
    TdApi.KeyboardButtonSource source = new TdApi.KeyboardButtonSourceWebApp(botId(), id);
    send(new TdApi.GetPreparedKeyboardButton(botId(), id), result -> {
      if (!(result instanceof TdApi.KeyboardButton)) { fail.run(); return; }
      TdApi.KeyboardButtonType type = ((TdApi.KeyboardButton) result).type;
      if (type instanceof TdApi.KeyboardButtonTypeRequestManagedBot) {
        managedBot((TdApi.KeyboardButtonTypeRequestManagedBot) type, source, completed, fail);
      } else if (type instanceof TdApi.KeyboardButtonTypeRequestChat) {
        TdApi.KeyboardButtonTypeRequestChat button = (TdApi.KeyboardButtonTypeRequestChat) type;
        chooseChats(R.string.WebAppChooseChatTitle, chat -> button.chatIsChannel ?
          chat.type instanceof TdApi.ChatTypeSupergroup && ((TdApi.ChatTypeSupergroup) chat.type).isChannel :
          chat.type instanceof TdApi.ChatTypeBasicGroup || chat.type instanceof TdApi.ChatTypeSupergroup && !((TdApi.ChatTypeSupergroup) chat.type).isChannel,
          false, 1, chats -> send(new TdApi.ShareChatWithBot(source, button.id, chats[0], true), checked -> {
            if (!(checked instanceof TdApi.Ok)) { error(checked); fail.run(); return; }
            confirm(R.string.WebAppShareChatTitle, host.context().getString(R.string.WebAppShareChatText, host.tdlib().chatTitle(chats[0]), botName()) +
              requestedDetails(button.requestTitle, button.requestUsername, button.requestPhoto) +
              (button.botAdministratorRights != null ? "\n\n" + host.context().getString(R.string.WebAppBotAdminConsent) + adminRights(button.botAdministratorRights) :
                button.botIsMember ? "\n\n" + host.context().getString(R.string.WebAppBotMemberConsent) : ""),
              R.string.WebAppShare, () -> send(new TdApi.ShareChatWithBot(source, button.id, chats[0], false), completed), fail);
          }), fail);
      } else if (type instanceof TdApi.KeyboardButtonTypeRequestUsers) {
        TdApi.KeyboardButtonTypeRequestUsers button = (TdApi.KeyboardButtonTypeRequestUsers) type;
        chooseChats(R.string.WebAppChooseUsersTitle, chat -> {
          if (!(chat.type instanceof TdApi.ChatTypePrivate)) return false;
          TdApi.User user = host.tdlib().cache().user(((TdApi.ChatTypePrivate) chat.type).userId);
          return user != null && (!(user.type instanceof TdApi.UserTypeDeleted)) &&
            (!button.restrictUserIsBot || button.userIsBot == (user.type instanceof TdApi.UserTypeBot)) &&
            (!button.restrictUserIsPremium || button.userIsPremium == user.isPremium);
        }, true, Math.max(1, button.maxQuantity), chats -> {
          long[] users = new long[chats.length];
          for (int i = 0; i < chats.length; i++) users[i] = ((TdApi.ChatTypePrivate) host.tdlib().chat(chats[i]).type).userId;
          send(new TdApi.ShareUsersWithBot(source, button.id, users, true), checked -> {
            if (!(checked instanceof TdApi.Ok)) { error(checked); fail.run(); return; }
            confirm(R.string.WebAppShareUsersTitle, host.context().getString(R.string.WebAppShareUsersText, users.length, botName()) +
              requestedDetails(button.requestName, button.requestUsername, button.requestPhoto), R.string.WebAppShare,
              () -> send(new TdApi.ShareUsersWithBot(source, button.id, users, false), completed), fail);
          });
        }, fail);
      } else fail.run();
    });
  }

  private String adminRights (TdApi.ChatAdministratorRights rights) {
    boolean[] values = {rights.canManageChat, rights.canChangeInfo, rights.canPostMessages, rights.canEditMessages,
      rights.canDeleteMessages, rights.canInviteUsers, rights.canRestrictMembers, rights.canPinMessages,
      rights.canManageTopics, rights.canPromoteMembers, rights.canManageVideoChats, rights.canPostStories,
      rights.canEditStories, rights.canDeleteStories, rights.canManageDirectMessages, rights.canManageTags,
      rights.canSendWelcomeMessages, rights.isAnonymous};
    int[] labels = {R.string.EventLogPromotedManageGroup, R.string.RightChangeGroupInfo, R.string.EventLogPromotedPostMessages,
      R.string.RightEditMessages, R.string.EditAdminGroupDeleteMessages, R.string.RightInviteViaLink, R.string.RightBanUsers,
      R.string.RightPinMessages, R.string.RightTopics, R.string.RightAddNewAdmins, R.string.RightVoiceChats,
      R.string.RightStoriesPost, R.string.RightStoriesEdit, R.string.RightStoriesDelete, R.string.RightDirectMessages,
      R.string.RightTags, R.string.EventLogPromotedManageWelcomeMessages, R.string.RightAnonymous};
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < values.length; i++) if (values[i]) result.append("\n• ").append(host.context().getString(labels[i]));
    return result.toString();
  }

  private String requestedDetails (boolean name, boolean username, boolean photo) {
    ArrayList<String> details = new ArrayList<>();
    if (name) details.add(host.context().getString(R.string.WebAppSharedName));
    if (username) details.add(host.context().getString(R.string.WebAppSharedUsername));
    if (photo) details.add(host.context().getString(R.string.WebAppSharedPhoto));
    return details.isEmpty() ? "" : "\n\n" + host.context().getString(R.string.WebAppSharedDetails, android.text.TextUtils.join(", ", details));
  }

  private void managedBot (TdApi.KeyboardButtonTypeRequestManagedBot button, TdApi.KeyboardButtonSource source,
                           Consumer<TdApi.Object> completed, Runnable cancel) {
    LinearLayout fields = column();
    EditText name = field(fields, R.string.WebAppBotName, button.suggestedName);
    EditText username = field(fields, R.string.WebAppBotUsername, button.suggestedUsername);
    AlertDialog dialog = show(new AlertDialog.Builder(host.activity()).setTitle(R.string.WebAppCreateBotTitle)
      .setMessage(host.context().getString(R.string.WebAppCreateBotText, botName())).setView(fields)
      .setPositiveButton(R.string.WebAppCreate, null).setNegativeButton(android.R.string.cancel, (d, w) -> cancel.run()), cancel);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      String botName = name.getText().toString().trim();
      String botUsername = username.getText().toString().trim();
      if (botName.isEmpty() || !botUsername.matches("[A-Za-z][A-Za-z0-9_]{1,28}[Bb][Oo][Tt]")) {
        username.setError(host.context().getString(R.string.WebAppInvalidBotUsername)); return;
      }
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      send(new TdApi.CreateBot(botId(), botName, botUsername, false), result -> {
        if (result instanceof TdApi.User) {
          dialog.dismiss();
          long createdUserId = ((TdApi.User) result).id;
          send(new TdApi.GetUserFullInfo(createdUserId), full -> {
            if (!(full instanceof TdApi.UserFullInfo) || ((TdApi.UserFullInfo) full).botInfo == null ||
                ((TdApi.UserFullInfo) full).botInfo.managerBotUserId != botId()) {
              error(full); cancel.run(); return;
            }
            send(new TdApi.ShareUsersWithBot(source, button.id, new long[] {createdUserId}, false), completed);
          });
        } else { error(result); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); }
      });
    });
  }

  interface ChatFilter { boolean accepts (TdApi.Chat chat); }
  void chooseChats (int title, ChatFilter filter, boolean multiple, int maximum, Consumer<long[]> chosen, Runnable cancel) {
    LinearLayout layout = column();
    EditText search = field(layout, R.string.WebAppSearchChats, "");
    ListView list = new ListView(host.activity());
    list.setChoiceMode(multiple ? ListView.CHOICE_MODE_MULTIPLE : ListView.CHOICE_MODE_SINGLE);
    layout.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
      Math.round(320 * host.context().getResources().getDisplayMetrics().density)));
    ArrayList<TdApi.Chat> chats = new ArrayList<>();
    ArrayList<String> names = new ArrayList<>();
    Set<Long> selected = new LinkedHashSet<>();
    ArrayAdapter<String> adapter = new ArrayAdapter<>(host.activity(), multiple ? android.R.layout.simple_list_item_multiple_choice :
      android.R.layout.simple_list_item_single_choice, names);
    list.setAdapter(adapter);
    AlertDialog.Builder builder = new AlertDialog.Builder(host.activity()).setTitle(title).setView(layout)
      .setNegativeButton(android.R.string.cancel, (d, w) -> cancel.run());
    if (multiple) builder.setPositiveButton(R.string.WebAppContinue, null);
    AlertDialog dialog = show(builder, cancel);
    if (multiple) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      if (selected.isEmpty()) return;
      long[] values = new long[selected.size()]; int i = 0;
      for (long chatId : selected) values[i++] = chatId;
      dialog.dismiss(); resolvePrivateChats(values, 0, chosen, cancel);
    });
    list.setOnItemClickListener((parent, view, position, rowId) -> {
      long chatId = chats.get(position).id;
      if (!multiple) { dialog.dismiss(); resolvePrivateChats(new long[] {chatId}, 0, chosen, cancel); return; }
      if (selected.contains(chatId)) selected.remove(chatId);
      else if (selected.size() < maximum) selected.add(chatId);
      else list.setItemChecked(position, false);
    });
    final int[] generation = {0};
    Consumer<String> searchChats = query -> {
      int token = ++generation[0];
      send(new TdApi.SearchChats(query, null, 200), result -> {
        if (!dialog.isShowing() || token != generation[0]) return;
        chats.clear(); names.clear(); list.clearChoices();
        if (result instanceof TdApi.Chats) {
          for (long chatId : ((TdApi.Chats) result).chatIds) {
            TdApi.Chat chat = host.tdlib().chat(chatId);
            if (chat != null && filter.accepts(chat)) { chats.add(chat); names.add(chat.title); }
          }
        }
        adapter.notifyDataSetChanged();
        for (int i = 0; i < chats.size(); i++) list.setItemChecked(i, selected.contains(chats.get(i).id));
        send(new TdApi.SearchContacts(query, 100), contacts -> {
          if (!dialog.isShowing() || token != generation[0] || !(contacts instanceof TdApi.Users)) return;
          Set<Long> existing = new LinkedHashSet<>();
          for (TdApi.Chat chat : chats) existing.add(chat.id);
          for (long userId : ((TdApi.Users) contacts).userIds) {
            long chatId = tgx.td.ChatId.fromUserId(userId);
            if (existing.contains(chatId)) continue;
            TdApi.User user = host.tdlib().cache().user(userId);
            if (user == null) continue;
            TdApi.Chat chat = host.tdlib().chat(chatId);
            if (chat == null) {
              chat = new TdApi.Chat(); chat.id = chatId; chat.type = new TdApi.ChatTypePrivate(userId);
              chat.title = (user.firstName + " " + user.lastName).trim();
            }
            if (filter.accepts(chat)) { chats.add(chat); names.add(chat.title); }
          }
          adapter.notifyDataSetChanged();
          for (int i = 0; i < chats.size(); i++) list.setItemChecked(i, selected.contains(chats.get(i).id));
        });
      });
    };
    search.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged (CharSequence s, int start, int count, int after) { }
      @Override public void onTextChanged (CharSequence s, int start, int before, int count) { searchChats.accept(s.toString()); }
      @Override public void afterTextChanged (Editable s) { }
    });
    searchChats.accept("");
  }

  private void resolvePrivateChats (long[] chatIds, int index, Consumer<long[]> chosen, Runnable cancel) {
    if (!alive()) return;
    if (index == chatIds.length) { chosen.accept(chatIds); return; }
    if (host.tdlib().chat(chatIds[index]) != null) { resolvePrivateChats(chatIds, index + 1, chosen, cancel); return; }
    send(new TdApi.CreatePrivateChat(tgx.td.ChatId.toUserId(chatIds[index]), false), result -> {
      if (result instanceof TdApi.Chat) resolvePrivateChats(chatIds, index + 1, chosen, cancel);
      else { error(result); cancel.run(); }
    });
  }

  LinearLayout column () {
    LinearLayout layout = new LinearLayout(host.activity());
    layout.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(16 * host.context().getResources().getDisplayMetrics().density);
    layout.setPadding(padding, 0, padding, 0);
    return layout;
  }
  EditText field (LinearLayout layout, int hint, String value) {
    EditText field = new EditText(host.activity()); field.setHint(hint); field.setSingleLine(true);
    if (value != null) field.setText(value);
    layout.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return field;
  }

  private final MessageListener messageListener = new MessageListener() {
    @Override public void onMessageSendSucceeded (TdApi.Message message, long oldId) { messageResult(message.chatId, oldId, true); }
    @Override public void onMessageSendFailed (TdApi.Message message, long oldId, TdApi.Error error) { messageResult(message.chatId, oldId, false); }
  };
  private void messageResult (long chatId, long id, boolean success) {
    host.runOnUiThread(() -> {
      Consumer<Boolean> callback = sentMessages.remove(chatId + ":" + id);
      if (alive() && callback != null) callback.accept(success);
    });
  }
  private void sendMessage (TdApi.Function<TdApi.Message> function, long chatId, Consumer<Boolean> callback) {
    if (observedChats.add(chatId)) host.tdlib().listeners().subscribeToMessageUpdates(chatId, messageListener);
    send(function, result -> {
      if (!(result instanceof TdApi.Message)) { callback.accept(false); return; }
      TdApi.Message message = (TdApi.Message) result;
      if (message.sendingState == null) callback.accept(true);
      else if (message.sendingState instanceof TdApi.MessageSendingStateFailed) callback.accept(false);
      else sentMessages.put(message.chatId + ":" + message.id, callback);
    });
  }

  private static String inlineResultId (TdApi.InlineQueryResult result) {
    switch (result.getConstructor()) {
      case TdApi.InlineQueryResultAnimation.CONSTRUCTOR: return ((TdApi.InlineQueryResultAnimation) result).id;
      case TdApi.InlineQueryResultArticle.CONSTRUCTOR: return ((TdApi.InlineQueryResultArticle) result).id;
      case TdApi.InlineQueryResultAudio.CONSTRUCTOR: return ((TdApi.InlineQueryResultAudio) result).id;
      case TdApi.InlineQueryResultContact.CONSTRUCTOR: return ((TdApi.InlineQueryResultContact) result).id;
      case TdApi.InlineQueryResultDocument.CONSTRUCTOR: return ((TdApi.InlineQueryResultDocument) result).id;
      case TdApi.InlineQueryResultGame.CONSTRUCTOR: return ((TdApi.InlineQueryResultGame) result).id;
      case TdApi.InlineQueryResultLocation.CONSTRUCTOR: return ((TdApi.InlineQueryResultLocation) result).id;
      case TdApi.InlineQueryResultPhoto.CONSTRUCTOR: return ((TdApi.InlineQueryResultPhoto) result).id;
      case TdApi.InlineQueryResultSticker.CONSTRUCTOR: return ((TdApi.InlineQueryResultSticker) result).id;
      case TdApi.InlineQueryResultVenue.CONSTRUCTOR: return ((TdApi.InlineQueryResultVenue) result).id;
      case TdApi.InlineQueryResultVideo.CONSTRUCTOR: return ((TdApi.InlineQueryResultVideo) result).id;
      case TdApi.InlineQueryResultVoiceNote.CONSTRUCTOR: return ((TdApi.InlineQueryResultVoiceNote) result).id;
      default: return null;
    }
  }

  public void onActivityResult (int requestCode, int resultCode, Intent data) { device.onActivityResult(requestCode, resultCode, data); }
  public void onRequestPermissionsResult (int requestCode, String[] permissions, int[] results) { device.onRequestPermissionsResult(requestCode, results); }
  public void destroy () {
    destroyed = true;
    payments.destroy(); stories.destroy(); device.destroy();
    for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
    dialogs.clear(); pending.clear(); sentMessages.clear();
    for (long chatId : observedChats) host.tdlib().listeners().unsubscribeFromMessageUpdates(chatId, messageListener);
    observedChats.clear();
  }
}
