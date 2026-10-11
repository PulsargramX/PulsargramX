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
import android.database.Cursor;
import android.provider.MediaStore;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.attach.MediaBottomFilesController;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.core.Media;
import org.thunderdog.challegram.data.InlineResultCommon;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.player.TGPlayerController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibProfileMusic;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.Permissions;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableData;

/** Select songs from Telegram or the device, without sending a placeholder chat message. */
public final class ProfileMusicPickerController extends RecyclerViewController<Void>
    implements View.OnClickListener, TGPlayerController.PlayListBuilder {
  private static final int CHATS = 0, SAVED = 1, DEVICE = 2, PROFILE = 3;
  private int source = CHATS;
  private SettingsAdapter adapter;
  private final ArrayList<Entry> entries = new ArrayList<>();
  private final Map<String, Entry> selected = new LinkedHashMap<>();
  private String query = "", nextOffset = "";
  private long nextMessageId;
  private int profileOffset, requestId;
  private boolean loading, endReached;
  private RunnableData<ArrayList<MediaBottomFilesController.MusicEntry>> onSelected;

  private static final class Entry {
    final String key;
    final InlineResultCommon row;
    final TdApi.InputAudio input;
    final MediaBottomFilesController.MusicEntry music;

    Entry (String key, InlineResultCommon row, TdApi.InputAudio input,
           MediaBottomFilesController.MusicEntry music) {
      this.key = key;
      this.row = row;
      this.input = input;
      this.music = music;
    }
  }

  public ProfileMusicPickerController (Context context, Tdlib tdlib) {
    super(context, tdlib);
    setNeedSearch();
  }

  public static void open (ViewController<?> parent) {
    parent.navigateTo(new ProfileMusicPickerController(parent.context(), parent.tdlib()));
  }

  public static void openProfile (ViewController<?> parent,
      RunnableData<ArrayList<MediaBottomFilesController.MusicEntry>> callback) {
    ProfileMusicPickerController picker = new ProfileMusicPickerController(parent.context(), parent.tdlib());
    picker.source = PROFILE;
    picker.onSelected = callback;
    parent.navigateTo(picker);
  }

  @Override
  public int getId () {
    return R.id.controller_profile_music_picker;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(onSelected != null ? R.string.ProfileMusicPlaylist : R.string.ProfileMusicAdd);
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recycler) {
    adapter = new SettingsAdapter(this, this, null);
    adapter.setInSelectMode(true, false, null);
    recycler.setLayoutManager(new LinearLayoutManager(context));
    recycler.setAdapter(adapter);
    recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled (RecyclerView view, int dx, int dy) {
        LinearLayoutManager manager = (LinearLayoutManager) view.getLayoutManager();
        if (manager.findLastVisibleItemPosition() + 8 >= adapter.getItemCount()) loadMore();
      }
    });
    recycler.post(this::reload);
  }

  @Override
  protected void onSearchInputChanged (String input) {
    query = input.trim();
    reload();
  }

  @Override
  protected void onLeaveSearchMode () {
    super.onLeaveSearchMode();
    query = "";
    reload();
  }

  private void reload () {
    requestId++;
    entries.clear();
    loading = false;
    endReached = false;
    nextOffset = "";
    nextMessageId = 0;
    profileOffset = 0;
    if (source == DEVICE) {
      boolean requesting = context().permissions().requestReadExternalStorage(Permissions.ReadType.AUDIO,
        grant -> {
          if (!isDestroyed() && source == DEVICE) {
            if (grant != Permissions.GrantResult.NONE) loadDevice();
            else { endReached = true; buildCells(); }
          }
        });
      if (!requesting) loadDevice();
    } else {
      loadMore();
    }
    buildCells();
  }

  private void loadMore () {
    if (loading || endReached || source == DEVICE) return;
    loading = true;
    final int context = requestId;
    if (source == PROFILE) {
      tdlib.send(new TdApi.GetUserProfileAudios(tdlib.myUserId(), profileOffset, 100),
        (audios, error) -> tdlib.uiExecute(() -> {
          if (context != requestId || isDestroyed()) return;
          loading = false;
          if (error != null) {
            UI.showError(error);
            endReached = true;
          } else {
            profileOffset += audios.audios.length;
            endReached = audios.audios.length == 0 || profileOffset >= audios.totalCount;
            for (TdApi.Audio audio : audios.audios) addAudio(audio,
              tdlib.profileMusic().message(tdlib.myUserId(), audio));
          }
          buildCells();
          if (!endReached && !query.isEmpty()) loadMore();
        }));
      return;
    }
    TdApi.Function<?> function = source == SAVED ?
      new TdApi.SearchChatMessages(tgx.td.ChatId.fromUserId(tdlib.myUserId()), null,
        query, null, nextMessageId, 0, 100, new TdApi.SearchMessagesFilterAudio()) :
      new TdApi.SearchMessages(null, query, nextOffset, 100,
        new TdApi.SearchMessagesFilterAudio(), null, 0, 0);
    tdlib.client().send(function, object -> tdlib.uiExecute(() -> {
      if (context != requestId || isDestroyed()) return;
      loading = false;
      TdApi.Message[] messages;
      if (object instanceof TdApi.FoundMessages) {
        TdApi.FoundMessages found = (TdApi.FoundMessages) object;
        messages = found.messages;
        nextOffset = found.nextOffset;
        endReached = StringUtils.isEmpty(nextOffset);
      } else if (object instanceof TdApi.FoundChatMessages) {
        TdApi.FoundChatMessages found = (TdApi.FoundChatMessages) object;
        messages = found.messages;
        nextMessageId = found.nextFromMessageId;
        endReached = nextMessageId == 0;
      } else {
        UI.showError(object);
        endReached = true;
        buildCells();
        return;
      }
      int oldCount = entries.size();
      for (TdApi.Message message : messages) {
        if (TdlibProfileMusic.canSave(message)) {
          addAudio(((TdApi.MessageAudio) message.content).audio, message);
        }
      }
      buildCells();
      if (!endReached && entries.size() == oldCount) loadMore();
    }));
  }

  private void addAudio (TdApi.Audio audio, TdApi.Message message) {
    String key = "remote_" + audio.audio.id;
    for (Entry entry : entries) if (entry.key.equals(key)) return;
    InlineResultCommon row = new InlineResultCommon(context(), tdlib, message,
      (TdApi.MessageAudio) message.content, this);
    row.setMessage(message);
    entries.add(new Entry(key, row, TdlibProfileMusic.input(audio),
      new MediaBottomFilesController.MusicEntry(audio)));
  }

  private void loadDevice () {
    if (loading || source != DEVICE) return;
    loading = true;
    final int context = requestId;
    Media.instance().post(() -> {
      ArrayList<Entry> songs = new ArrayList<>();
      String[] columns = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.ALBUM_ID};
      try (Cursor cursor = context().getContentResolver().query(
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
          MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
          MediaStore.Audio.Media.TITLE + " COLLATE NOCASE")) {
        while (cursor != null && cursor.moveToNext()) {
          String path = cursor.getString(3);
          if (StringUtils.isEmpty(path)) continue;
          MediaBottomFilesController.MusicEntry music = new MediaBottomFilesController.MusicEntry(
            cursor.getLong(0), cursor.getString(1) != null ? cursor.getString(1) : "",
            cursor.getString(2) != null ? cursor.getString(2) : "", path, cursor.getLong(4),
            cursor.getString(5), cursor.getLong(6));
          InlineResultCommon row = new InlineResultCommon(context(), tdlib, music, this);
          row.setMessage(row.getPlayPauseMessage());
          songs.add(new Entry("local_" + path, row, new TdApi.InputAudio(
            TD.createInputFile(path, music.getMimeType()), null,
            (int) (music.getDuration() / 1000), music.getTitle(), music.getArtist()), music));
        }
      } catch (Exception error) {
        UI.showToast(R.string.AccessError, android.widget.Toast.LENGTH_SHORT);
      }
      tdlib.uiExecute(() -> {
        if (context != requestId || isDestroyed()) return;
        entries.addAll(songs);
        loading = false;
        endReached = true;
        buildCells();
      });
    });
  }

  private void buildCells () {
    ArrayList<ListItem> items = new ArrayList<>();
    if (onSelected == null) {
      items.add(new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_musicFiles, 0,
        R.string.ProfileMusicFromChats).setSelected(source == CHATS));
      items.add(new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_saveToSavedMessages, 0,
        R.string.SavedMessages).setSelected(source == SAVED));
      items.add(new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_file, 0,
        R.string.ProfileMusicFromDevice).setSelected(source == DEVICE));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    }
    String normalized = query.toLowerCase(Locale.ROOT);
    for (Entry entry : entries) {
      if (!matches(entry, normalized)) continue;
      items.add(new ListItem(ListItem.TYPE_CUSTOM_INLINE, R.id.btn_music)
        .setData(entry.row).setStringValue(entry.key).setSelected(selected.containsKey(entry.key)));
    }
    if (loading) items.add(new ListItem(ListItem.TYPE_PROGRESS));
    else if (endReached && items.size() <= (onSelected == null ? 4 : 0)) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.NoMusicFilesFound));
    } else if (!endReached) {
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_retry, 0, R.string.ProfileMusicLoadMore));
    }
    adapter.setItems(items, false);
    setDoneVisible(!selected.isEmpty(), true);
  }

  private boolean matches (Entry entry, String normalized) {
    return (source != DEVICE && source != PROFILE) || normalized.isEmpty() ||
      (entry.music.getTitle() + " " + entry.music.getArtist()).toLowerCase(Locale.ROOT).contains(normalized);
  }

  @Override
  public TGPlayerController.PlayList buildPlayList (TdApi.Message fromMessage) {
    ArrayList<TdApi.Message> tracks = new ArrayList<>();
    int origin = -1;
    String normalized = query.toLowerCase(Locale.ROOT);
    for (Entry entry : entries) {
      if (!matches(entry, normalized)) continue;
      TdApi.Message track = entry.row.getMessage();
      if (TGPlayerController.compareTracks(track, fromMessage)) origin = tracks.size();
      tracks.add(track);
    }
    TGPlayerController.PlayList playlist = new TGPlayerController.PlayList(tracks, origin);
    if (source == PROFILE) {
      return playlist.setProfileMusicSource(tdlib.myUserId(), profileOffset, endReached)
        .setSearchQuery(query);
    }
    return playlist.setReachedEnds(true, true);
  }

  @Override
  public boolean wouldReusePlayList (TdApi.Message fromMessage, boolean reverse,
      boolean altered, List<TdApi.Message> tracks, long chatId) {
    return source == PROFILE && tdlib.context().player().isProfileMusicSource(
      tdlib, tdlib.myUserId(), query);
  }

  @Override
  public void onClick (View view) {
    ListItem item = (ListItem) view.getTag();
    if (item == null) return;
    if (item.getViewType() == ListItem.TYPE_CUSTOM_INLINE) {
      String key = item.getStringValue();
      if (selected.remove(key) == null) {
        for (Entry entry : entries) if (entry.key.equals(key)) selected.put(key, entry);
      }
      buildCells();
    } else if (item.getId() == R.id.btn_retry) loadMore();
    else {
      source = item.getId() == R.id.btn_musicFiles ? CHATS :
        item.getId() == R.id.btn_saveToSavedMessages ? SAVED : DEVICE;
      reload();
    }
  }

  @Override
  protected void onDoneClick () {
    if (selected.isEmpty()) return;
    if (onSelected != null) {
      ArrayList<MediaBottomFilesController.MusicEntry> songs = new ArrayList<>(selected.size());
      for (Entry entry : selected.values()) songs.add(entry.music);
      onSelected.runWithData(songs);
    } else {
      TdApi.InputAudio[] songs = new TdApi.InputAudio[selected.size()];
      int index = 0;
      for (Entry entry : selected.values()) {
        songs[index++] = entry.music.profileAudio != null ? entry.input :
          tdlib.filegen().createThumbnail(new TdApi.InputMessageAudio(entry.input, null), false).audio;
      }
      tdlib.profileMusic().addAll(songs, null);
    }
    navigateBack();
  }
}
