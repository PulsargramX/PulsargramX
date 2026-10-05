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
package org.thunderdog.challegram.telegram;

import android.app.AlertDialog;
import android.net.Uri;
import android.widget.CheckBox;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.webapp.WebAppLaunchRequest;
import org.thunderdog.challegram.component.webapp.WebAppSession;
import org.thunderdog.challegram.component.webapp.WebAppTheme;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.ui.MessagesController.ReplyInfo;
import org.thunderdog.challegram.navigation.NavigationStack;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.ChatsController;
import org.thunderdog.challegram.ui.MessagesController;
import org.thunderdog.challegram.ui.WebAppController;
import org.thunderdog.challegram.unsorted.Settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.Destroyable;
import tgx.td.ChatId;
import tgx.td.Td;

/** Account-scoped launch orchestration; all mutable state is owned by the UI thread. */
public final class TdlibWebAppManager {
  private final Tdlib tdlib;
  private final ArrayList<WebAppSession> sessions = new ArrayList<>();
  private final IdentityHashMap<WebAppSession, WebAppController> controllers = new IdentityHashMap<>();
  private final Set<WebAppController> closingControllers = new HashSet<>();
  private final Set<Long> clearingBots = new HashSet<>();
  private final ArrayList<PendingLaunch> pending = new ArrayList<>();
  private final Set<Runnable> listeners = new HashSet<>();
  private volatile TdApi.AttachmentMenuBot[] attachmentMenuBots = new TdApi.AttachmentMenuBot[0];
  private int generation;

  public TdlibWebAppManager (Tdlib tdlib) {
    this.tdlib = tdlib;
  }

  public TdApi.AttachmentMenuBot[] getAttachmentMenuBots () {
    return attachmentMenuBots.clone();
  }

  public void addListener (Runnable listener) {
    listeners.add(listener);
  }

  public void removeListener (Runnable listener) {
    listeners.remove(listener);
  }

  private void notifyChanged () {
    for (Runnable listener : new ArrayList<>(listeners)) {
      listener.run();
    }
  }

  public List<WebAppSession> getOpenSessions () {
    ArrayList<WebAppSession> result = new ArrayList<>();
    for (WebAppSession session : sessions) {
      if (!session.isClosed()) result.add(session);
    }
    return result;
  }

  public boolean isMinimized (WebAppSession session) {
    WebAppController controller = controllers.get(session);
    return !session.isClosed() && controller != null && !controller.isDestroyed() &&
      controller.isMinimized();
  }

  public void closeBotSessions (long botId) {
    for (PendingLaunch launch : new ArrayList<>(pending)) {
      if (launch.request.botUserId == botId) launch.finish();
    }
    for (WebAppSession session : new ArrayList<>(sessions)) {
      if (session.request.botUserId == botId) session.close();
    }
  }

  private void afterControllersDestroyed (long botId, Runnable after) {
    Set<WebAppController> waiting = new HashSet<>(controllers.values());
    waiting.addAll(closingControllers);
    waiting.removeIf(controller -> controller.isDestroyed() ||
      botId != 0 && controller.getArgumentsStrict().request.botUserId != botId);
    if (waiting.isEmpty()) {
      UI.post(after);
      return;
    }
    for (WebAppController controller : new ArrayList<>(waiting)) {
      controller.addDestroyListener(() -> {
        waiting.remove(controller);
        if (waiting.isEmpty()) UI.post(after);
      });
    }
  }

  public void forgetBot (long botId, boolean removeFromMenu, Runnable onDone) {
    long userId = tdlib.myUserId();
    int version = generation;
    Runnable clear = () -> {
      if (userId != tdlib.myUserId() || version != generation || !tdlib.isAuthorized()) return;
      if (!clearingBots.add(botId)) return;
      afterControllersDestroyed(botId, () -> {
        if (version != generation || userId != tdlib.myUserId()) {
          clearingBots.remove(botId);
          return;
        }
        Settings.instance().remove(consentKey(botId));
        org.thunderdog.challegram.component.webapp.WebAppActions.clearBotData(UI.getAppContext(), tdlib, botId);
        org.thunderdog.challegram.component.webapp.WebAppDevice.forget(UI.getAppContext(), tdlib, botId, () -> {
          clearingBots.remove(botId);
          notifyChanged();
          if (onDone != null && version == generation && userId == tdlib.myUserId()) onDone.run();
        });
      });
      closeBotSessions(botId);
    };
    if (removeFromMenu) {
      tdlib.send(new TdApi.ToggleBotIsAddedToAttachmentMenu(botId, false, false), (result, error) -> UI.post(() -> {
        if (userId != tdlib.myUserId() || version != generation) return;
        if (error != null) UI.showError(error);
        else clear.run();
      }));
    } else {
      clear.run();
    }
  }

  public void verifyAge (ViewController<?> owner, java.util.function.Consumer<Boolean> callback) {
    TdApi.AgeVerificationParameters parameters = tdlib.ageVerificationParameters();
    if (parameters == null) {
      callback.accept(true);
      return;
    }
    WebAppLaunchRequest request = new WebAppLaunchRequest(WebAppLaunchRequest.Source.AGE_VERIFICATION, 0);
    request.botUsername = parameters.verificationBotUsername;
    request.onAgeVerified = callback;
    open(owner, request);
  }

  public void minimize (WebAppController controller) {
    WebAppSession session = controller.getArgumentsStrict();
    if (session.isClosed()) {
      controller.close(true);
      return;
    }
    if (controller.context().navigation().getCurrentStackItem() != controller ||
        !controller.navigateBack()) {
      controller.restore();
      return;
    }
    notifyChanged();
  }

  public void restore (ViewController<?> owner, WebAppSession session) {
    WebAppController controller = controllers.get(session);
    if (owner.tdlib() != tdlib || owner.isDestroyed() || session.isClosed() ||
        controller == null || controller.isDestroyed()) return;
    org.thunderdog.challegram.navigation.NavigationController navigation = owner.context().navigation();
    if (navigation.isAnimating()) return;
    NavigationStack stack = navigation.getStack();
    if (stack.getCurrent() == controller) {
      controller.restore();
      notifyChanged();
      return;
    }
    // A covered controller can still belong to the stack even though its view is detached.
    // Remove that exact entry before moving the retained instance to the foreground.
    for (int index = stack.size() - 1; index >= 0; index--) {
      if (stack.get(index) == controller) {
        stack.remove(index);
        break;
      }
    }
    if (controller.isAttachedToNavigationController()) navigation.removeChildWrapper(controller);
    controller.restore();
    if (!navigation.navigateTo(controller)) session.close();
    notifyChanged();
  }

  private void onSessionClosed (WebAppSession session) {
    sessions.remove(session);
    WebAppController controller = controllers.remove(session);
    if (controller != null && !controller.isDestroyed()) {
      closingControllers.add(controller);
      controller.addDestroyListener(() -> closingControllers.remove(controller));
      controller.close(true);
    }
    notifyChanged();
  }

  public void onAttachmentMenuBots (TdApi.AttachmentMenuBot[] bots) {
    UI.post(() -> {
      attachmentMenuBots = bots.clone();
      notifyChanged();
    });
  }

  public void onMessageSent (long launchId) {
    UI.post(() -> {
      for (WebAppSession session : new ArrayList<>(sessions)) {
        if (session.launchId == launchId) {
          session.close();
        }
      }
    });
  }

  public void onSessionExpired (long launchId) {
    UI.post(() -> {
      boolean found = false;
      for (WebAppSession session : new ArrayList<>(sessions)) {
        if (session.launchId == launchId) {
          found = true;
          session.close();
        }
      }
      if (found && tdlib.isCurrent()) {
        UI.showToast(R.string.WebAppSessionExpired, android.widget.Toast.LENGTH_LONG);
      }
    });
  }

  public void onChatJoinResult (TdApi.UpdateChatJoinResult update) {
    UI.post(() -> {
      boolean handled = false;
      for (PendingLaunch launch : new ArrayList<>(pending)) {
        if (launch.request.source == WebAppLaunchRequest.Source.GUARD &&
            launch.request.queryId == update.queryId) {
          handled = true;
          launch.finish();
        }
      }
      for (WebAppSession session : new ArrayList<>(sessions)) {
        if (session.request.source == WebAppLaunchRequest.Source.GUARD &&
            session.request.queryId == update.queryId) {
          handled = true;
          session.close();
        }
      }
      if (!handled || !tdlib.isCurrent()) return;
      if (update.chatId != 0 && update.result instanceof TdApi.ChatJoinRequestResultApproved) {
        org.thunderdog.challegram.BaseActivity activity = UI.getUiContext();
        ViewController<?> context = activity != null ? activity.navigation().getCurrentStackItem() : null;
        if (context != null && context.tdlib() == tdlib) {
          tdlib.ui().openChat(context, update.chatId, new TdlibUi.ChatOpenParameters().keepStack());
        }
      } else if (update.result instanceof TdApi.ChatJoinRequestResultDeclined) {
        UI.showToast(R.string.WebAppLaunchJoinDeclined, android.widget.Toast.LENGTH_LONG);
      } else if (update.result instanceof TdApi.ChatJoinRequestResultQueued) {
        UI.showToast(R.string.WebAppLaunchJoinQueued, android.widget.Toast.LENGTH_LONG);
      }
    });
  }

  public void reset (boolean loggingOut) {
    UI.post(() -> {
      generation++;
      for (PendingLaunch launch : new ArrayList<>(pending)) {
        launch.finish();
      }
      if (loggingOut) {
        Settings.instance().removeByPrefix("webapp_consent_" + tdlib.id() + "_", null);
        afterControllersDestroyed(0, () -> {
          org.thunderdog.challegram.component.webapp.WebAppDevice.forgetAccount(UI.getAppContext(), tdlib, null);
          org.thunderdog.challegram.component.webapp.WebAppActions.clearBotData(UI.getAppContext(), tdlib, 0);
        });
      }
      for (WebAppSession session : new ArrayList<>(sessions)) {
        session.close();
      }
      attachmentMenuBots = new TdApi.AttachmentMenuBot[0];
      notifyChanged();
    });
  }

  public void open (ViewController<?> context, WebAppLaunchRequest request) {
    if (!UI.inUiThread()) {
      UI.post(() -> open(context, request));
      return;
    }
    if (context == null) {
      if (request.onFinished != null) request.onFinished.run();
      return;
    }
    for (PendingLaunch old : new ArrayList<>(pending)) {
      if (old.context == context) {
        old.finish();
      }
    }
    PendingLaunch launch = new PendingLaunch(context, request);
    pending.add(launch);
    if (!launch.alive()) {
      launch.finish();
      return;
    }
    if (request.chatId != 0 && ChatId.isSecret(request.chatId)) {
      launch.fail(R.string.WebAppLaunchSecretChat);
      return;
    }
    if (request.source == WebAppLaunchRequest.Source.BOT_MENU && !request.url.isEmpty()) {
      String link = request.url.startsWith("menu://") ? request.url.substring(7) : request.url;
      tdlib.send(new TdApi.GetInternalLinkType(link), (type, error) -> UI.post(() -> {
        if (!launch.alive()) return;
        if (type instanceof TdApi.InternalLinkTypeWebApp || type instanceof TdApi.InternalLinkTypeMainWebApp) {
          WebAppLaunchRequest redirected = fromLink(type);
          redirected.chatId = launch.request.chatId;
          redirected.topicId = launch.request.topicId;
          redirected.replyTo = launch.request.replyTo;
          redirected.originalUrl = link;
          redirected.hiddenLink = launch.request.hiddenLink;
          launch.request = redirected;
        }
        resolveBot(launch);
      }));
    } else {
      resolveBot(launch);
    }
  }

  public void openLink (ViewController<?> context, TdApi.InternalLinkType type,
                        @Nullable String originalUrl, @Nullable TdlibUi.UrlOpenParameters options) {
    WebAppLaunchRequest request = fromLink(type);
    request.originalUrl = originalUrl != null ? originalUrl : "";
    request.hiddenLink = options != null && options.requireOpenPrompt;
    if (options != null && options.messageId != null) {
      request.chatId = options.messageId.getChatId();
    } else if (context instanceof MessagesController) {
      request.chatId = ((MessagesController) context).getChatId();
    }
    if (context instanceof MessagesController &&
        ((MessagesController) context).getChatId() == request.chatId) {
      request.topicId = ((MessagesController) context).getMessageTopicId();
      ReplyInfo reply = ((MessagesController) context).getCurrentReplyId();
      request.replyTo = reply != null ? reply.toInputMessageReply() : null;
    }
    if (context instanceof WebAppController) {
      ViewController<?> fallback = ((WebAppController) context).getWebAppLinkFallback();
      if (fallback != null) context = fallback;
    }
    open(context, request);
  }

  private static WebAppLaunchRequest fromLink (TdApi.InternalLinkType type) {
    WebAppLaunchRequest request;
    if (type instanceof TdApi.InternalLinkTypeWebApp) {
      TdApi.InternalLinkTypeWebApp link = (TdApi.InternalLinkTypeWebApp) type;
      request = new WebAppLaunchRequest(WebAppLaunchRequest.Source.LINK, 0);
      request.botUsername = link.botUsername;
      request.shortName = link.webAppShortName;
      request.startParameter = link.startParameter;
      request.mode = link.mode;
    } else if (type instanceof TdApi.InternalLinkTypeMainWebApp) {
      TdApi.InternalLinkTypeMainWebApp link = (TdApi.InternalLinkTypeMainWebApp) type;
      request = new WebAppLaunchRequest(WebAppLaunchRequest.Source.MAIN, 0);
      request.botUsername = link.botUsername;
      request.startParameter = link.startParameter;
      request.mode = link.mode;
    } else {
      TdApi.InternalLinkTypeAttachmentMenuBot link = (TdApi.InternalLinkTypeAttachmentMenuBot) type;
      request = new WebAppLaunchRequest(WebAppLaunchRequest.Source.ATTACHMENT_MENU, 0);
      request.botUsername = link.botUsername;
      request.url = link.url;
      request.targetChat = link.targetChat;
    }
    if (request.mode == null) request.mode = new TdApi.WebAppOpenModeFullSize();
    return request;
  }

  private void resolveBot (PendingLaunch launch) {
    if (!launch.alive()) return;
    if (launch.request.botUserId == 0) {
      tdlib.send(new TdApi.SearchPublicChat(launch.request.botUsername), (chat, error) -> UI.post(() -> {
        if (!launch.alive()) return;
        if (error != null) {
          launch.fail(error);
        } else if (chat.type instanceof TdApi.ChatTypePrivate) {
          launch.request.botUserId = ((TdApi.ChatTypePrivate) chat.type).userId;
          resolveBot(launch);
        } else {
          launch.fail(R.string.WebAppLaunchUnavailable);
        }
      }));
      return;
    }
    tdlib.send(new TdApi.GetUser(launch.request.botUserId), (user, error) -> UI.post(() -> {
      if (!launch.alive()) return;
      if (clearingBots.contains(launch.request.botUserId)) {
        launch.fail(R.string.WebAppLaunchUnavailable);
        return;
      }
      if (error != null) {
        launch.fail(error);
      } else if (!(user.type instanceof TdApi.UserTypeBot)) {
        launch.fail(R.string.WebAppLaunchUnavailable);
      } else if (user.restrictionInfo != null &&
                 !StringUtils.isEmpty(user.restrictionInfo.restrictionReason)) {
        launch.fail(user.restrictionInfo.restrictionReason);
      } else {
        launch.bot = user;
        if (launch.request.botUsername.isEmpty()) {
          launch.request.botUsername = Td.primaryUsername(user);
        }
        TdApi.UserTypeBot bot = (TdApi.UserTypeBot) user.type;
        if ((launch.request.source == WebAppLaunchRequest.Source.MAIN ||
             launch.request.source == WebAppLaunchRequest.Source.AGE_VERIFICATION) && !bot.hasMainWebApp) {
          launch.fail(R.string.WebAppLaunchUnavailable);
          return;
        }
        if (bot.canBeAddedToAttachmentMenu && (launch.request.source == WebAppLaunchRequest.Source.MAIN ||
            launch.request.source == WebAppLaunchRequest.Source.LINK ||
            launch.request.source == WebAppLaunchRequest.Source.ATTACHMENT_MENU ||
            launch.request.source == WebAppLaunchRequest.Source.SIDE_MENU)) {
          tdlib.send(new TdApi.GetAttachmentMenuBot(user.id), (attachment, attachmentError) -> UI.post(() -> {
            if (!launch.alive()) return;
            if (attachmentError != null) {
              launch.fail(attachmentError);
            } else {
              launch.attachment = attachment;
              resolveTarget(launch);
            }
          }));
        } else if (launch.request.source == WebAppLaunchRequest.Source.ATTACHMENT_MENU ||
                   launch.request.source == WebAppLaunchRequest.Source.SIDE_MENU) {
          launch.fail(R.string.WebAppLaunchUnavailable);
        } else {
          resolveTarget(launch);
        }
      }
    }));
  }

  private void resolveTarget (PendingLaunch launch) {
    if (!launch.alive()) return;
    if (launch.context.context().navigation().isAnimating()) {
      UI.post(() -> resolveTarget(launch), 80);
      return;
    }
    TdApi.TargetChat target = launch.request.targetChat;
    launch.request.targetChat = null;
    if (target instanceof TdApi.TargetChatChosen ||
        (launch.request.source == WebAppLaunchRequest.Source.ATTACHMENT_MENU &&
         launch.request.chatId == 0 && !(target instanceof TdApi.TargetChatInternalLink))) {
      TdApi.TargetChatTypes types = target instanceof TdApi.TargetChatChosen ?
        ((TdApi.TargetChatChosen) target).types : new TdApi.TargetChatTypes(true, true, true, true);
      ChatsController picker = new ChatsController(launch.context.context(), tdlib);
      picker.setArguments(new ChatsController.Arguments(chat ->
        acceptsChat(tdlib, chat, types) && supportsChat(chat, launch.attachment),
        (chat, onDone) -> {
          if (launch.targetChosen || !launch.alive()) return false;
          launch.targetChosen = true;
          launch.request.chatId = chat.id;
          launch.request.topicId = null;
          launch.request.replyTo = null;
          showTargetChat(launch, chat);
          return false;
        }));
      launch.setContext(picker);
      if (!picker.context().navigation().navigateTo(picker)) launch.finish();
      return;
    }
    if (target instanceof TdApi.TargetChatInternalLink) {
      resolveTargetLink(launch, ((TdApi.TargetChatInternalLink) target).link);
      return;
    }
    if (launch.request.hasChatSession() && launch.request.chatId == 0) {
      tdlib.send(new TdApi.CreatePrivateChat(launch.request.botUserId, false), (chat, error) -> UI.post(() -> {
        if (!launch.alive()) return;
        if (error != null) launch.fail(error);
        else {
          launch.request.chatId = chat.id;
          prepareConsent(launch);
        }
      }));
    } else {
      prepareConsent(launch);
    }
  }

  private void resolveTargetLink (PendingLaunch launch, TdApi.InternalLinkType link) {
    if (link instanceof TdApi.InternalLinkTypeUserPhoneNumber) {
      tdlib.send(new TdApi.SearchUserByPhoneNumber(
        ((TdApi.InternalLinkTypeUserPhoneNumber) link).phoneNumber, false), (user, error) -> UI.post(() -> {
          if (!launch.alive()) return;
          if (error != null) launch.fail(error);
          else tdlib.send(new TdApi.CreatePrivateChat(user.id, false), (chat, chatError) -> UI.post(() -> {
            if (!launch.alive()) return;
            if (chatError != null) launch.fail(chatError);
            else {
              launch.request.chatId = chat.id;
              launch.request.topicId = null;
              launch.request.replyTo = null;
              showTargetChat(launch, chat);
            }
          }));
        }));
    } else if (link instanceof TdApi.InternalLinkTypePublicChat) {
      tdlib.send(new TdApi.SearchPublicChat(((TdApi.InternalLinkTypePublicChat) link).chatUsername),
        (chat, error) -> UI.post(() -> {
          if (!launch.alive()) return;
          if (error != null) launch.fail(error);
          else {
            launch.request.chatId = chat.id;
            launch.request.topicId = null;
            launch.request.replyTo = null;
            showTargetChat(launch, chat);
          }
        }));
    } else if (link instanceof TdApi.InternalLinkTypeMessage) {
      tdlib.send(new TdApi.GetMessageLinkInfo(((TdApi.InternalLinkTypeMessage) link).url),
        (info, error) -> UI.post(() -> {
          if (!launch.alive()) return;
          if (error != null) launch.fail(error);
          else {
            launch.request.chatId = info.chatId;
            launch.request.topicId = info.topicId;
            launch.request.replyTo = null;
            TdApi.Chat chat = tdlib.chat(info.chatId);
            if (chat != null) showTargetChat(launch, chat);
            else launch.fail(R.string.WebAppLaunchChatUnavailable);
          }
        }));
    } else {
      launch.fail(R.string.WebAppLaunchChatUnavailable);
    }
  }

  private void showTargetChat (PendingLaunch launch, TdApi.Chat chat) {
    if (!launch.alive()) return;
    if (launch.context.context().navigation().isAnimating()) {
      UI.post(() -> showTargetChat(launch, chat), 80);
      return;
    }
    if (!supportsChat(chat, launch.attachment)) {
      launch.fail(R.string.WebAppLaunchChatUnavailable);
      return;
    }
    MessagesController controller = new MessagesController(launch.context.context(), tdlib);
    controller.setArguments(new MessagesController.Arguments(tdlib, null, chat, null,
      launch.request.topicId, null));
    controller.addOneShotFocusListener(() -> prepareConsent(launch));
    launch.setContext(controller);
    if (!controller.context().navigation().navigateTo(controller)) launch.finish();
  }

  public static boolean acceptsChat (Tdlib tdlib, TdApi.Chat chat, TdApi.TargetChatTypes types) {
    if (chat == null || chat.type instanceof TdApi.ChatTypeSecret) return false;
    if (chat.type instanceof TdApi.ChatTypePrivate) {
      return tdlib.isBotChat(chat) ? types.allowBotChats : types.allowUserChats;
    }
    return tdlib.isChannel(chat.id) ? types.allowChannelChats : types.allowGroupChats;
  }

  private boolean supportsChat (TdApi.Chat chat, @Nullable TdApi.AttachmentMenuBot bot) {
    if (chat == null || chat.type instanceof TdApi.ChatTypeSecret || bot == null) return false;
    if (chat.id == ChatId.fromUserId(tdlib.myUserId())) return bot.supportsSelfChat;
    if (chat.type instanceof TdApi.ChatTypePrivate) {
      return tdlib.isBotChat(chat) ? bot.supportsBotChats : bot.supportsUserChats;
    }
    return tdlib.isChannel(chat.id) ? bot.supportsChannelChats : bot.supportsGroupChats;
  }

  private void prepareConsent (PendingLaunch launch) {
    if (!launch.alive()) return;
    if (launch.request.chatId != 0 && ChatId.isSecret(launch.request.chatId)) {
      launch.fail(R.string.WebAppLaunchSecretChat);
      return;
    }
    if (launch.request.source == WebAppLaunchRequest.Source.ATTACHMENT_MENU &&
        !supportsChat(tdlib.chat(launch.request.chatId), launch.attachment)) {
      launch.fail(R.string.WebAppLaunchChatUnavailable);
      return;
    }
    if (launch.request.source == WebAppLaunchRequest.Source.LINK) {
      tdlib.send(new TdApi.SearchWebApp(launch.request.botUserId, launch.request.shortName),
        (app, error) -> UI.post(() -> {
          if (!launch.alive()) return;
          if (error != null) launch.fail(error);
          else {
            launch.found = app;
            confirm(launch);
          }
        }));
    } else {
      confirm(launch);
    }
  }

  private void confirm (PendingLaunch launch) {
    if (!launch.alive()) return;
    boolean needsAdding = launch.attachment != null && !launch.attachment.isAdded;
    boolean writeRequested = (launch.found != null && launch.found.requestWriteAccess) ||
      (needsAdding && launch.attachment.requestWriteAccess);
    boolean seen = Settings.instance().getBoolean(consentKey(launch.request.botUserId), false);
    boolean mustConfirm = needsAdding || writeRequested || launch.request.hiddenLink ||
      (launch.found != null ? !launch.found.skipConfirmation : !seen);
    if (!mustConfirm) {
      requestUrl(launch, false);
      return;
    }
    String name = TD.getUserName(launch.bot);
    CheckBox writeAccess = new CheckBox(launch.context.context());
    writeAccess.setText(Lang.getString(R.string.WebAppLaunchWriteAccess));
    writeAccess.setPadding(Screen.dp(24), Screen.dp(8), Screen.dp(24), Screen.dp(8));
    writeAccess.setChecked(false);
    final boolean[] accepted = {false};
    AlertDialog.Builder builder = new AlertDialog.Builder(launch.context.context())
      .setTitle(Lang.getString(R.string.WebAppLaunchTitle))
      .setMessage(Lang.getString(needsAdding ? R.string.WebAppLaunchAddDisclaimer :
        R.string.WebAppLaunchDisclaimer, name))
      .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> launch.finish())
      .setNeutralButton(Lang.getString(R.string.WebAppLaunchTerms), (dialog, which) ->
        Intents.openUri("https://telegram.org/tos/mini-apps"))
      .setPositiveButton(Lang.getString(needsAdding ? R.string.WebAppLaunchAdd : R.string.Open),
        (dialog, which) -> {
          accepted[0] = true;
          if (!launch.alive()) return;
          Settings.instance().putBoolean(consentKey(launch.request.botUserId), true);
          boolean allowWrite = writeRequested && writeAccess.isChecked();
          if (needsAdding) {
            tdlib.send(new TdApi.ToggleBotIsAddedToAttachmentMenu(launch.request.botUserId, true, allowWrite),
              (result, error) -> UI.post(() -> {
                if (!launch.alive()) return;
                if (error != null) launch.fail(error);
                else requestUrl(launch, allowWrite);
              }));
          } else {
            requestUrl(launch, allowWrite);
          }
        });
    if (writeRequested) builder.setView(writeAccess);
    launch.dialog = launch.context.showAlert(builder);
    if (launch.dialog == null) {
      launch.finish();
    } else {
      launch.dialog.setOnDismissListener(dialog -> {
        launch.dialog = null;
        if (!accepted[0]) launch.finish();
      });
    }
  }

  private String consentKey (long botId) {
    return "webapp_consent_" + tdlib.id() + "_" + (tdlib.isProduction() ? "prod_" : "test_") +
      tdlib.myUserId() + "_" + botId;
  }

  private void requestUrl (PendingLaunch launch, boolean allowWrite) {
    if (!launch.alive() || launch.urlRequested) return;
    launch.urlRequested = true;
    WebAppLaunchRequest request = launch.request;
    TdApi.WebAppOpenParameters parameters = new TdApi.WebAppOpenParameters(themeParameters(), "android", request.mode);
    TdApi.Function<?> function;
    switch (request.source) {
      case KEYBOARD:
      case INLINE_QUERY:
      case SIDE_MENU:
        function = new TdApi.GetWebAppUrl(request.botUserId, request.url, parameters);
        break;
      case LINK:
        function = new TdApi.GetWebAppLinkUrl(request.chatId, request.botUserId, request.shortName,
          request.startParameter, allowWrite, parameters);
        break;
      case MAIN:
      case AGE_VERIFICATION:
        function = new TdApi.GetMainWebApp(request.chatId, request.botUserId, request.startParameter, parameters);
        break;
      case GUARD:
        function = new TdApi.GetGuardBotWebAppUrl(request.queryId, parameters);
        break;
      default:
        function = new TdApi.OpenWebApp(request.chatId, request.botUserId, request.url,
          request.topicId, request.replyTo, parameters);
        break;
    }
    tdlib.client().send(function, result -> UI.post(() -> onUrlReceived(launch, result)));
  }

  private void onUrlReceived (PendingLaunch launch, TdApi.Object result) {
    long launchId = result instanceof TdApi.WebAppInfo ? ((TdApi.WebAppInfo) result).launchId : 0;
    if (!launch.alive()) {
      if (launchId != 0 && launch.userId == tdlib.myUserId() && launch.version == generation &&
          tdlib.isAuthorized()) tdlib.send(new TdApi.CloseWebApp(launchId), (ok, error) -> { });
      return;
    }
    if (launch.context.context().navigation().isAnimating()) {
      UI.post(() -> onUrlReceived(launch, result), 80);
      return;
    }
    WebAppLaunchRequest request = launch.request;
    TdApi.WebAppUrl url;
    TdApi.WebAppOpenMode mode = request.mode;
    if (result instanceof TdApi.WebAppInfo) url = ((TdApi.WebAppInfo) result).url;
    else if (result instanceof TdApi.WebAppUrl) url = (TdApi.WebAppUrl) result;
    else if (result instanceof TdApi.MainWebApp) {
      url = ((TdApi.MainWebApp) result).url;
      mode = ((TdApi.MainWebApp) result).mode;
    } else {
      if (result instanceof TdApi.Error) launch.fail((TdApi.Error) result);
      else launch.fail(R.string.WebAppLaunchUnavailable);
      return;
    }
    if (!isWebUrl(url.url)) {
      if (launchId != 0) tdlib.send(new TdApi.CloseWebApp(launchId), (ok, error) -> { });
      launch.fail(R.string.WebAppLaunchUnavailable);
      return;
    }
    final WebAppSession[] holder = new WebAppSession[1];
    WebAppSession session = new WebAppSession(tdlib, request, url, launchId, mode,
      () -> onSessionClosed(holder[0]));
    holder[0] = session;
    sessions.add(session);
    WebAppController controller = new WebAppController(launch.context.context(), tdlib);
    controller.setArguments(session);
    controllers.put(session, controller);
    if (!launch.context.context().navigation().navigateTo(controller)) {
      session.close();
    }
    notifyChanged();
    launch.finish();
  }

  private static boolean isWebUrl (String url) {
    try {
      Uri uri = Uri.parse(url);
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) &&
        !StringUtils.isEmpty(uri.getHost()) && uri.getUserInfo() == null;
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static TdApi.ThemeParameters themeParameters () {
    return WebAppTheme.parameters();
  }

  private final class PendingLaunch {
    ViewController<?> context;
    WebAppLaunchRequest request;
    final long userId = tdlib.myUserId();
    final int version = generation;
    final @Nullable Runnable onFinished;
    boolean finished;
    boolean targetChosen;
    boolean urlRequested;
    final Destroyable onContextDestroyed = () -> UI.post(() -> {
      if (context.isDestroyed() && !followClosedWebApp()) finish();
    });
    final org.thunderdog.challegram.BaseActivity.PasscodeListener onPasscode = (activity, showing) -> {
      if (showing) finish();
    };
    final NavigationStack.ChangeListener onStackChanged = stack -> UI.post(() -> {
      if (!finished && stack.getCurrent() != context && !followClosedWebApp()) finish();
    });
    @Nullable TdApi.User bot;
    @Nullable TdApi.AttachmentMenuBot attachment;
    @Nullable TdApi.FoundWebApp found;
    @Nullable AlertDialog dialog;

    PendingLaunch (ViewController<?> context, WebAppLaunchRequest request) {
      this.context = context;
      this.request = new WebAppLaunchRequest(request);
      this.onFinished = request.onFinished;
      context.addDestroyListener(onContextDestroyed);
      context.context().addPasscodeListener(onPasscode);
      context.context().navigation().getStack().addChangeListener(onStackChanged);
    }

    void setContext (ViewController<?> next) {
      context.removeDestroyListener(onContextDestroyed);
      context = next;
      context.addDestroyListener(onContextDestroyed);
    }

    boolean followClosedWebApp () {
      if (finished || !(context instanceof WebAppController)) return false;
      ViewController<?> fallback = ((WebAppController) context).getWebAppLinkFallback();
      if (fallback == null) return false;
      setContext(fallback);
      return true;
    }

    boolean alive () {
      if (finished) return false;
      followClosedWebApp();
      if (version != generation || context.isDestroyed() || context.tdlib() != tdlib || !tdlib.isAuthorized() ||
          tdlib.myUserId() != userId || !tdlib.isCurrent() || context.context().isPasscodeShowing()) {
        finish();
        return false;
      }
      return true;
    }

    void fail (int message) {
      fail(Lang.getString(message));
    }

    void fail (TdApi.Error error) {
      fail(TD.toErrorString(error));
    }

    void fail (CharSequence message) {
      if (alive()) context.openAlert(R.string.WebAppLaunchTitle, message);
      finish();
    }

    void finish () {
      if (finished) return;
      finished = true;
      pending.remove(this);
      context.removeDestroyListener(onContextDestroyed);
      context.context().removePasscodeListener(onPasscode);
      context.context().navigation().getStack().removeChangeListener(onStackChanged);
      if (dialog != null) {
        dialog.dismiss();
        dialog = null;
      }
      if (onFinished != null) onFinished.run();
    }
  }
}
