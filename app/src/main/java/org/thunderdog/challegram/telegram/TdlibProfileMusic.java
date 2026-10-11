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
package org.thunderdog.challegram.telegram;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.ShareController;
import org.thunderdog.challegram.widget.ProfileMusicUndoView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import me.vkryl.core.lambda.RunnableData;
import tgx.td.ChatId;

/** Account-scoped profile music operations. Profile tracks never have a chat message source. */
public final class TdlibProfileMusic implements CleanupStartupDelegate {
  private static final AtomicLong MESSAGE_IDS = new AtomicLong(Long.MIN_VALUE);

  public interface Listener {
    void onProfileMusicChanged ();
  }

  public static final class Message extends TdApi.Message {
    public final long profileUserId;

    private Message (long userId, long messageId, TdApi.Audio audio) {
      profileUserId = userId;
      id = messageId;
      content = new TdApi.MessageAudio(audio, new TdApi.FormattedText("", new TdApi.TextEntity[0]));
      canBeSaved = true;
    }
  }

  private final Tdlib tdlib;
  private final Map<String, Long> messageIds = new HashMap<>();
  private final List<Listener> listeners = new ArrayList<>();
  private final ArrayDeque<Runnable> mutations = new ArrayDeque<>();
  private int generation;
  private boolean mutating;

  TdlibProfileMusic (Tdlib tdlib) {
    this.tdlib = tdlib;
    tdlib.listeners().addCleanupListener(this);
  }

  public synchronized Message message (long userId, TdApi.Audio audio) {
    String key = userId + "_" + audio.audio.id;
    Long id = messageIds.get(key);
    if (id == null) {
      id = MESSAGE_IDS.getAndIncrement();
      messageIds.put(key, id);
    }
    return new Message(userId, id, audio);
  }

  public static long userId (@Nullable TdApi.Message message) {
    return message instanceof Message ? ((Message) message).profileUserId : 0;
  }

  public static boolean canSave (@Nullable TdApi.Message message) {
    return message != null && message.content instanceof TdApi.MessageAudio &&
      (message instanceof Message || (message.canBeSaved && message.id > 0 &&
        message.chatId != 0 && !ChatId.isSecret(message.chatId) &&
        message.selfDestructType == null));
  }

  public static TdApi.InputAudio input (TdApi.Audio audio) {
    return new TdApi.InputAudio(new TdApi.InputFileId(audio.audio.id), null,
      audio.duration, audio.title, audio.performer);
  }

  public void search (long userId, String query, BooleanSupplier valid,
      Tdlib.ResultHandler<TdApi.Audios> callback) {
    int context = generation;
    searchPage(userId, query.toLowerCase(Locale.ROOT), 0, new ArrayList<>(),
      () -> context == generation && valid.getAsBoolean(), callback);
  }

  private void searchPage (long userId, String query, int offset,
      ArrayList<TdApi.Audio> matches, BooleanSupplier valid,
      Tdlib.ResultHandler<TdApi.Audios> callback) {
    if (!valid.getAsBoolean()) return;
    tdlib.send(new TdApi.GetUserProfileAudios(userId, offset, 100), (audios, error) ->
      tdlib.uiExecute(() -> {
        if (!valid.getAsBoolean()) return;
        if (error != null) {
          callback.onResult(null, error);
          return;
        }
        for (TdApi.Audio audio : audios.audios) {
          if ((TD.getTitle(audio) + " " + TD.getSubtitle(audio)).toLowerCase(Locale.ROOT).contains(query)) {
            matches.add(audio);
          }
        }
        int nextOffset = offset + audios.audios.length;
        if (audios.audios.length == 0 || nextOffset >= audios.totalCount) {
          callback.onResult(new TdApi.Audios(matches.size(), matches.toArray(new TdApi.Audio[0])), null);
        } else {
          searchPage(userId, query, nextOffset, matches, valid, callback);
        }
      }));
  }

  public void addListener (Listener listener) {
    listeners.add(listener);
  }

  public void removeListener (Listener listener) {
    listeners.remove(listener);
  }

  public void check (int fileId, RunnableData<Boolean> callback) {
    int context = generation;
    tdlib.send(new TdApi.IsProfileAudio(fileId), (ok, error) -> tdlib.uiExecute(() -> {
      if (context != generation) return;
      if (error != null && error.code != 404) {
        UI.showError(error);
        return;
      }
      callback.runWithData(error == null);
    }));
  }

  private void mutate (TdApi.Function<TdApi.Ok> function, @Nullable Runnable done) {
    mutate(function, done, null);
  }

  private void mutate (TdApi.Function<TdApi.Ok> function, @Nullable Runnable done,
      @Nullable RunnableData<Boolean> completed) {
    int context = generation;
    mutations.add(() -> tdlib.send(function, (ok, error) -> tdlib.uiExecute(() -> {
      if (context != generation) return;
      if (error != null) {
        UI.showError(error);
        tdlib.context().player().refreshProfileMusic(tdlib);
      } else {
        if (done != null) done.run();
        if (context != generation) return;
        tdlib.context().player().refreshProfileMusic(tdlib);
        for (Listener listener : new ArrayList<>(listeners)) {
          listener.onProfileMusicChanged();
        }
        tdlib.send(new TdApi.GetUserFullInfo(tdlib.myUserId()), (info, infoError) -> { });
      }
      if (completed != null) completed.runWithData(error == null);
      mutating = false;
      runNextMutation();
    })));
    runNextMutation();
  }

  private void runNextMutation () {
    if (!mutating && !mutations.isEmpty()) {
      mutating = true;
      mutations.remove().run();
    }
  }

  public void add (TdApi.InputAudio audio, @Nullable Runnable done) {
    mutate(new TdApi.AddProfileAudio(audio), done);
  }

  public void addAll (TdApi.Message[] messages, @Nullable Runnable done) {
    if (messages.length == 0) return;
    TdApi.InputAudio[] audios = new TdApi.InputAudio[messages.length];
    int index = 0;
    for (TdApi.Message message : messages) {
      if (!canSave(message)) {
        UI.showToast(R.string.ChannelNoSave, android.widget.Toast.LENGTH_SHORT);
        return;
      }
      audios[index++] = input(((TdApi.MessageAudio) message.content).audio);
    }
    addAll(audios, done);
  }

  public void addAll (TdApi.InputAudio[] audios, @Nullable Runnable done) {
    if (audios.length == 0) return;
    int[] remaining = {audios.length};
    boolean[] success = {true};
    // Each addition moves to the beginning; preserve the user's selection order.
    for (int i = audios.length - 1; i >= 0; i--) {
      mutate(new TdApi.AddProfileAudio(audios[i]), null, saved -> {
        success[0] &= saved;
        if (--remaining[0] == 0 && success[0]) {
          UI.showToast(R.string.AudioAddedToProfile, android.widget.Toast.LENGTH_SHORT);
          if (done != null) done.run();
        }
      });
    }
  }

  public void remove (ViewController<?> controller, TdApi.Audio audio, int afterFileId) {
    int context = generation;
    remove(audio, () -> ProfileMusicUndoView.show(controller, () -> {
      if (context != generation) return;
      add(input(audio), () -> {
        if (afterFileId != 0) move(audio.audio.id, afterFileId, null);
      });
    }));
  }

  private void removeWithUndo (ViewController<?> controller, TdApi.Audio audio,
      int offset, int previousFileId, int context) {
    tdlib.send(new TdApi.GetUserProfileAudios(tdlib.myUserId(), offset, 100), (audios, error) ->
      tdlib.uiExecute(() -> {
        if (context != generation || controller.isDestroyed()) return;
        if (error != null) {
          UI.showError(error);
          return;
        }
        int previous = previousFileId;
        for (TdApi.Audio song : audios.audios) {
          if (song.audio.id == audio.audio.id) {
            remove(controller, audio, previous);
            return;
          }
          previous = song.audio.id;
        }
        int nextOffset = offset + audios.audios.length;
        if (audios.audios.length != 0 && nextOffset < audios.totalCount) {
          removeWithUndo(controller, audio, nextOffset, previous, context);
        }
      }));
  }

  public void remove (TdApi.Audio audio, @Nullable Runnable done) {
    mutate(new TdApi.RemoveProfileAudio(audio.audio.id), done);
  }

  public void move (int fileId, int afterFileId, @Nullable Runnable done) {
    mutate(new TdApi.SetProfileAudioPosition(fileId, afterFileId), done);
  }

  public void toggle (ViewController<?> controller, TdApi.Message message) {
    if (!canSave(message)) return;
    TdApi.Audio audio = ((TdApi.MessageAudio) message.content).audio;
    check(audio.audio.id, saved -> {
      if (controller.isDestroyed()) return;
      if (saved) {
        removeWithUndo(controller, audio, 0, 0, generation);
      } else {
        add(input(audio), () -> UI.showToast(R.string.AudioAddedToProfile, android.widget.Toast.LENGTH_SHORT));
      }
    });
  }

  public void showSaveOptions (ViewController<?> controller, TdApi.Message message) {
    if (!canSave(message)) return;
    check(TD.getFileId(message), saved -> {
      if (controller.isDestroyed()) return;
      controller.showOptions(Lang.getString(R.string.AudioSaveTo),
        new int[] {R.id.btn_profileAudio, R.id.btn_saveToSavedMessages, R.id.btn_saveFile},
        new String[] {Lang.getString(saved ? R.string.AudioRemoveFromProfile : R.string.AudioAddToProfile),
          Lang.getString(R.string.SavedMessages), Lang.getString(R.string.SaveToMusic)},
        new int[] {saved ? ViewController.OptionColor.RED : ViewController.OptionColor.NORMAL,
          ViewController.OptionColor.NORMAL, ViewController.OptionColor.NORMAL},
        new int[] {R.drawable.baseline_music_note_24, R.drawable.baseline_bookmark_24,
          R.drawable.baseline_file_download_24}, (view, id) -> {
          TdApi.Audio audio = ((TdApi.MessageAudio) message.content).audio;
          if (id == R.id.btn_profileAudio) {
            toggle(controller, message);
          } else if (id == R.id.btn_saveToSavedMessages) {
            int context = generation;
            tdlib.privateChat(tdlib.myUserId(), chat -> {
              if (context != generation || controller.isDestroyed() || chat == null) return;
              tdlib.client().send(new TdApi.SendMessage(chat.id, null, null,
                null, null, new TdApi.InputMessageAudio(input(audio), null)), tdlib.messageHandler());
            });
          } else if (id == R.id.btn_saveFile) {
            TD.DownloadedFile file = TD.getDownloadedFile(tdlib, message);
            if (file != null) TD.saveFile(controller.context(), file);
            else {
              int context = generation;
              tdlib.send(new TdApi.DownloadFile(audio.audio.id, 32, 0, 0, true),
                (downloaded, error) -> tdlib.uiExecute(() -> {
                  if (context != generation || controller.isDestroyed()) return;
                  if (error != null) UI.showError(error);
                  else {
                    audio.audio = downloaded;
                    TD.DownloadedFile ready = TD.getDownloadedFile(tdlib, message);
                    if (ready != null) TD.saveFile(controller.context(), ready);
                  }
                }));
            }
          }
          return true;
        });
    });
  }

  public void share (ViewController<?> controller, TdApi.Audio audio) {
    ShareController share = new ShareController(controller.context(), tdlib);
    share.setArguments(new ShareController.Args(new TdApi.InputMessageAudio(input(audio), null)));
    share.show();
  }

  @Override
  public void onPerformUserCleanup () {
    tdlib.uiExecute(() -> {
      generation++;
      synchronized (this) {
        messageIds.clear();
      }
      mutations.clear();
      mutating = false;
    });
  }
}
