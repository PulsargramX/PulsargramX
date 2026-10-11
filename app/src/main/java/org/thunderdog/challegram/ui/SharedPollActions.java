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

import android.app.AlertDialog;
import android.view.View;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.config.ClientPolicy;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.PollResultPolicy;
import org.thunderdog.challegram.data.SharedPollVotePolicy;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.mediaview.MediaViewController;
import org.thunderdog.challegram.mediaview.data.MediaItem;
import org.thunderdog.challegram.mediaview.data.PollMediaGallery;
import org.thunderdog.challegram.navigation.SettingsWrapBuilder;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.telegram.TdlibFilesManager;

import java.io.File;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import tgx.td.MessageId;
import tgx.td.Td;

/** Native archive actions use fresh message snapshots without creating a hidden chat controller. */
final class SharedPollActions {
  private final SharedCommonController owner;
  private final Tdlib tdlib;
  private final long chatId, messageId, pollId;
  private final View anchor;

  SharedPollActions (SharedCommonController owner, TdApi.Message message, View anchor) {
    this.owner = owner;
    this.tdlib = owner.tdlib();
    this.chatId = message.chatId;
    this.messageId = message.id;
    this.pollId = ((TdApi.MessagePoll) message.content).poll.id;
    this.anchor = anchor;
  }

  void show () {
    fresh(message -> tdlib.send(new TdApi.GetMessageProperties(chatId, messageId),
      (properties, error) -> tdlib.ui().post(() -> {
        if (owner.isDestroyed()) return;
        if (error != null) { UI.showError(error); return; }
        showMenu(message, properties);
      })));
  }

  private void fresh (Consumer<TdApi.Message> action) {
    tdlib.send(new TdApi.GetMessage(chatId, messageId), (message, error) -> tdlib.ui().post(() -> {
      if (owner.isDestroyed()) return;
      if (error != null) { UI.showError(error); return; }
      if (message.content.getConstructor() != TdApi.MessagePoll.CONSTRUCTOR ||
          ((TdApi.MessagePoll) message.content).poll.id != pollId) return;
      owner.editMessage(messageId, message.content);
      action.accept(message);
    }));
  }

  private void showMenu (TdApi.Message message, TdApi.MessageProperties properties) {
    TdApi.MessagePoll content = (TdApi.MessagePoll) message.content;
    TdApi.Poll poll = content.poll;
    List<Integer> ids = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    if (SharedPollVotePolicy.canVote(poll)) {
      add(ids, labels, 1, R.string.Vote);
      if (SharedPollVotePolicy.hasAnswer(poll)) add(ids, labels, 2, R.string.RetractVote);
    }
    if (poll.canSeeResults) add(ids, labels, 3, R.string.ViewPollResults);
    if (PollResultPolicy.canGetVoters(poll.isAnonymous, poll.canSeeResults, poll.canGetVoters)) {
      add(ids, labels, 4, R.string.PollResultsTitle);
    }
    if (properties.canGetPollVoteStatistics) add(ids, labels, 5, R.string.PollVoteStatistics);
    if (content.media != null || hasOptionMedia(poll)) add(ids, labels, 6, R.string.Open);
    if (poll.canSeeResults && poll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR) {
      TdApi.PollTypeQuiz quiz = (TdApi.PollTypeQuiz) poll.type;
      if ((quiz.explanation != null && !quiz.explanation.text.isEmpty()) || quiz.explanationMedia != null) {
        add(ids, labels, 8, R.string.QuizExplanation);
      }
    }
    add(ids, labels, 7, R.string.ShowInChat);
    owner.showOptions(TD.toCharSequence(poll.question), integers(ids), labels.toArray(new String[0]),
      (view, id) -> {
        if (owner.isDestroyed()) return true;
        if (id == 7) { showInChat(); return true; }
        fresh(current -> {
          TdApi.Poll latest = ((TdApi.MessagePoll) current.content).poll;
          if (id == 1 && SharedPollVotePolicy.canVote(latest)) showVote(current);
          else if (id == 2) submit(Collections.emptyList(), true, false);
          else if (id == 3 && latest.canSeeResults) showResults(current);
          else if (id == 4) showVoters(current, null);
          else if (id == 5) {
            PollStatisticsController controller = new PollStatisticsController(owner.context(), tdlib);
            controller.setArguments(new PollStatisticsController.Args(chatId, messageId));
            owner.navigateTo(controller);
          } else if (id == 6) showMedia(current);
          else if (id == 8) showExplanation(current);
        });
        return true;
      });
  }

  private void showVote (TdApi.Message message) {
    TdApi.MessagePoll content = (TdApi.MessagePoll) message.content;
    TdApi.Poll poll = content.poll;
    List<ListItem> items = new ArrayList<>();
    String[] stableIds = new String[poll.options.length];
    int[] order = SharedPollVotePolicy.displayOrder(poll);
    for (int i = 0; i < order.length; i++) {
      TdApi.PollOption option = poll.options[order[i]];
      stableIds[i] = option.id;
      items.add(poll.allowsMultipleAnswers ?
        new ListItem(ListItem.TYPE_CHECKBOX_OPTION_MULTILINE, i + 1, 0,
          TD.toCharSequence(option.text), option.isChosen) :
        new ListItem(ListItem.TYPE_RADIO_OPTION, i + 1, 0,
          TD.toCharSequence(option.text), R.id.btn_pollVote, option.isChosen));
    }
    owner.showSettings(new SettingsWrapBuilder(R.id.btn_pollVote)
      .addHeaderItem(TD.toCharSequence(poll.question))
      .addHeaderItem(TD.toCharSequence(content.description))
      .setRawItems(items).setSaveStr(R.string.Vote).setIntDelegate((id, result) -> {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < stableIds.length; i++) {
          if (poll.allowsMultipleAnswers ? result.get(i + 1) != 0 :
              result.get(R.id.btn_pollVote) == i + 1) selected.add(stableIds[i]);
        }
        if (!selected.isEmpty()) submit(selected, false, false);
      }));
  }

  private void submit (List<String> stableIds, boolean retract, boolean confirmedIdentity) {
    fresh(message -> {
      TdApi.Poll poll = ((TdApi.MessagePoll) message.content).poll;
      int[] indexes = SharedPollVotePolicy.resolve(poll, stableIds, retract);
      if (indexes == null) {
        owner.openAlert(R.string.Poll, Lang.getString(R.string.PollVoteRestricted));
        return;
      }
      TdApi.Chat chat = tdlib.chat(chatId);
      boolean anonymousSender = chat != null && (chat.messageSenderId != null ?
        !tdlib.isSelfSender(chat.messageSenderId) : tdlib.isAnonymousAdmin(chatId));
      if (!confirmedIdentity && !poll.isAnonymous && Config.PROTECT_ANONYMOUS_VOTING && anonymousSender) {
        owner.showAlert(new AlertDialog.Builder(owner.context(), Theme.dialogTheme())
          .setMessage(Lang.getString(R.string.AnonWarning))
          .setNegativeButton(Lang.getString(R.string.Cancel), null)
          .setPositiveButton(Lang.getString(R.string.Vote),
            (dialog, which) -> submit(stableIds, retract, true)));
        return;
      }
      tdlib.send(new TdApi.SetPollAnswer(chatId, messageId, indexes), (ok, error) ->
        tdlib.ui().post(() -> {
          if (!owner.isDestroyed()) {
            if (error != null) UI.showError(error);
            else fresh(updated -> showResultsIfAvailable(updated));
          }
        }));
    });
  }

  private void showResultsIfAvailable (TdApi.Message message) {
    if (((TdApi.MessagePoll) message.content).poll.canSeeResults) showResults(message);
  }

  private void showResults (TdApi.Message message) {
    TdApi.MessagePoll content = (TdApi.MessagePoll) message.content;
    TdApi.Poll poll = content.poll;
    if (!poll.canSeeResults) return;
    String[] labels = new String[poll.options.length];
    String[] stableIds = new String[poll.options.length];
    int[] order = SharedPollVotePolicy.displayOrder(poll);
    for (int i = 0; i < order.length; i++) {
      TdApi.PollOption option = poll.options[order[i]];
      stableIds[i] = option.id;
      boolean correct = poll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR &&
        isCorrectOption(
          ((TdApi.PollTypeQuiz) poll.type).correctOptionIds, order[i]);
      labels[i] = (correct ? "✓ " : option.isChosen ? "● " : "") + option.text.text + " — " +
        option.votePercentage + "% · " + Lang.plural(R.string.xVotes, option.voterCount);
    }
    owner.showAlert(new AlertDialog.Builder(owner.context(), Theme.dialogTheme())
      .setTitle(TD.toCharSequence(poll.question)).setItems(labels,
        (dialog, which) -> fresh(latest -> showOption(latest, stableIds[which])))
      .setPositiveButton(Lang.getOK(), null));
  }

  private static boolean isCorrectOption (int[] correctIds, int index) {
    if (correctIds != null) for (int id : correctIds) if (id == index) return true;
    return false;
  }

  private void showExplanation (TdApi.Message message) {
    TdApi.Poll poll = ((TdApi.MessagePoll) message.content).poll;
    if (!poll.canSeeResults || poll.type.getConstructor() != TdApi.PollTypeQuiz.CONSTRUCTOR) return;
    TdApi.PollTypeQuiz quiz = (TdApi.PollTypeQuiz) poll.type;
    AlertDialog.Builder builder = new AlertDialog.Builder(owner.context(), Theme.dialogTheme())
      .setTitle(Lang.getString(R.string.QuizExplanation))
      .setMessage(TD.toCharSequence(quiz.explanation))
      .setPositiveButton(Lang.getOK(), null);
    if (quiz.explanationMedia != null) {
      builder.setNeutralButton(Lang.getString(R.string.Open), (dialog, which) -> fresh(latest -> {
        TdApi.Poll updated = ((TdApi.MessagePoll) latest.content).poll;
        if (updated.canSeeResults && updated.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR) {
          openMedia(latest, ((TdApi.PollTypeQuiz) updated.type).explanationMedia);
        }
      }));
    }
    owner.showAlert(builder);
  }

  private void showOption (TdApi.Message message, String stableId) {
    TdApi.Poll poll = ((TdApi.MessagePoll) message.content).poll;
    TdApi.PollOption selected = null;
    for (TdApi.PollOption option : poll.options) {
      if (stableId.equals(option.id)) { selected = option; break; }
    }
    if (selected == null) return;
    List<Integer> ids = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    if (PollResultPolicy.canGetVoters(poll.isAnonymous, poll.canSeeResults, poll.canGetVoters)) {
      add(ids, labels, 1, R.string.PollResultsTitle);
    }
    if (selected.media != null) add(ids, labels, 2, R.string.Open);
    if (ids.isEmpty()) return;
    owner.showOptions(TD.toCharSequence(selected.text), integers(ids), labels.toArray(new String[0]),
      (view, id) -> {
        fresh(latest -> {
          if (id == 1) showVoters(latest, stableId);
          else {
            for (TdApi.PollOption option : ((TdApi.MessagePoll) latest.content).poll.options) {
              if (stableId.equals(option.id)) { openMedia(latest, option.media); break; }
            }
          }
        });
        return true;
      });
  }

  private void showVoters (TdApi.Message message, String optionId) {
    TdApi.Poll poll = ((TdApi.MessagePoll) message.content).poll;
    if (!PollResultPolicy.canGetVoters(poll.isAnonymous, poll.canSeeResults, poll.canGetVoters)) return;
    PollResultsController controller = new PollResultsController(owner.context(), tdlib);
    controller.setArguments(new PollResultsController.Args(poll, chatId, messageId, optionId));
    owner.navigateTo(controller);
  }

  private void showMedia (TdApi.Message message) {
    TdApi.MessagePoll content = (TdApi.MessagePoll) message.content;
    List<String> labels = new ArrayList<>();
    List<String> optionIds = new ArrayList<>();
    if (content.media != null) { labels.add(Lang.getString(R.string.Poll)); optionIds.add(null); }
    for (TdApi.PollOption option : content.poll.options) {
      if (option.media != null) { labels.add(option.text.text); optionIds.add(option.id); }
    }
    owner.showAlert(new AlertDialog.Builder(owner.context(), Theme.dialogTheme())
      .setTitle(TD.toCharSequence(content.poll.question)).setItems(labels.toArray(new String[0]),
        (dialog, which) -> fresh(latest -> {
          TdApi.MessagePoll current = (TdApi.MessagePoll) latest.content;
          String stableId = optionIds.get(which);
          if (stableId == null) openMedia(latest, current.media);
          else for (TdApi.PollOption option : current.poll.options) {
            if (stableId.equals(option.id)) { openMedia(latest, option.media); break; }
          }
        })));
  }

  private void openMedia (TdApi.Message message, TdApi.PollMedia media) {
    if (media == null) return;
    if (message.selfDestructType != null) { showInChat(); return; }
    MediaItem item = null;
    switch (media.getConstructor()) {
      case TdApi.PollMediaPhoto.CONSTRUCTOR: {
        TdApi.PollMediaPhoto photo = (TdApi.PollMediaPhoto) media;
        item = photo.video != null ? MediaItem.valueOf(owner.context(), tdlib, photo.video,
          null, photo.photo, null) : MediaItem.valueOf(owner.context(), tdlib, photo.photo, null);
        break;
      }
      case TdApi.PollMediaVideo.CONSTRUCTOR: {
        TdApi.PollMediaVideo video = (TdApi.PollMediaVideo) media;
        item = MediaItem.valueOf(owner.context(), tdlib, video.video, video.alternativeVideos, video.cover, null);
        break;
      }
      case TdApi.PollMediaAnimation.CONSTRUCTOR:
        item = MediaItem.valueOf(owner.context(), tdlib, ((TdApi.PollMediaAnimation) media).animation, null);
        break;
      case TdApi.PollMediaDocument.CONSTRUCTOR: {
        TdApi.Document document = ((TdApi.PollMediaDocument) media).document;
        item = MediaItem.valueOf(owner.context(), tdlib, document, null);
        if (item == null && ClientPolicy.canSaveMessage(message.canBeSaved, false)) {
          tdlib.files().downloadFile(document.document, TdlibFilesManager.PRIORITY_USER_REQUEST_DOWNLOAD,
            (file, error) -> tdlib.ui().post(() -> {
              if (owner.isDestroyed()) return;
              if (error != null) { UI.showError(error); return; }
              if (TD.isFileLoaded(file)) fresh(latest -> {
                if (latest.selfDestructType == null &&
                    ClientPolicy.canSaveMessage(latest.canBeSaved, false)) {
                  U.openFile(owner, document.fileName, new File(file.local.path), document.mimeType, 0);
                }
              });
            }));
          return;
        }
        break;
      }
      case TdApi.PollMediaAudio.CONSTRUCTOR: {
        TdApi.Message audio = Td.copyOf(message);
        audio.content = new TdApi.MessageAudio(((TdApi.PollMediaAudio) media).audio,
          new TdApi.FormattedText("", null));
        tdlib.context().player().playPauseMessage(tdlib, audio, owner);
        return;
      }
      case TdApi.PollMediaLink.CONSTRUCTOR:
        tdlib.ui().openUrl(owner, ((TdApi.PollMediaLink) media).url, new TdlibUi.UrlOpenParameters());
        return;
      case TdApi.PollMediaSticker.CONSTRUCTOR:
        tdlib.ui().showStickerSet(owner, ((TdApi.PollMediaSticker) media).sticker.setId,
          new TdlibUi.UrlOpenParameters());
        return;
      case TdApi.PollMediaLocation.CONSTRUCTOR: {
        TdApi.Location location = ((TdApi.PollMediaLocation) media).location;
        tdlib.ui().openMap(owner, new MapController.Args(location.latitude, location.longitude, message));
        return;
      }
      case TdApi.PollMediaVenue.CONSTRUCTOR: {
        TdApi.Location location = ((TdApi.PollMediaVenue) media).venue.location;
        tdlib.ui().openMap(owner, new MapController.Args(location.latitude, location.longitude, message));
        return;
      }
    }
    if (item != null && message.selfDestructType == null) {
      MediaViewController.openFromPoll(owner, message, null,
        PollMediaGallery.indexOf((TdApi.MessagePoll) message.content, media));
    } else showInChat();
  }

  private void showInChat () {
    tdlib.ui().openMessage(owner, chatId, new MessageId(chatId, messageId),
      new TdlibUi.UrlOpenParameters().tooltip(owner.context().tooltipManager().builder(anchor)));
  }

  private static boolean hasOptionMedia (TdApi.Poll poll) {
    for (TdApi.PollOption option : poll.options) if (option.media != null) return true;
    return false;
  }

  private static void add (List<Integer> ids, List<String> labels, int id, int resource) {
    ids.add(id); labels.add(Lang.getString(resource));
  }

  private static int[] integers (List<Integer> ids) {
    int[] result = new int[ids.size()];
    for (int i = 0; i < result.length; i++) result[i] = ids.get(i);
    return result;
  }
}
