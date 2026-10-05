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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import androidx.media3.transformer.Transformer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.component.webapp.WebAppMediaPreparation;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.mediaview.MediaViewController;
import org.thunderdog.challegram.mediaview.data.MediaItem;
import org.thunderdog.challegram.mediaview.data.MediaStack;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

/** Public preview gallery and owner tools for each preview language. */
public final class BotMediaPreviewController extends RecyclerViewController<Void> implements View.OnClickListener, View.OnLongClickListener {
  private static final int CONTROLLER_ID = androidx.core.view.ViewCompat.generateViewId();
  private static final int PREVIEW_ID = androidx.core.view.ViewCompat.generateViewId();
  private static final int LANGUAGE_ID = androidx.core.view.ViewCompat.generateViewId();
  private static final int ADD_ID = androidx.core.view.ViewCompat.generateViewId();
  private static final int PICK_MEDIA = 0x5761;
  private final long botUserId;
  private final boolean editable;
  private SettingsAdapter adapter;
  private TdApi.BotMediaPreview[] previews = new TdApi.BotMediaPreview[0];
  private String languageCode = "";
  private String[] languages = new String[0];
  private boolean busy;
  private volatile int generation;
  private final long ownerUserId;
  private Transformer transformer;
  private File preparingSource, preparingOutput;

  public BotMediaPreviewController (Context context, Tdlib tdlib, TdApi.User bot) {
    super(context, tdlib);
    botUserId = bot.id;
    ownerUserId = tdlib.myUserId();
    editable = TD.canEditBot(bot) && bot.type instanceof TdApi.UserTypeBot &&
      ((TdApi.UserTypeBot) bot.type).hasMainWebApp;
  }

  @Override public int getId () { return CONTROLLER_ID; }
  @Override public CharSequence getName () { return Lang.getString(R.string.WebAppPreviews); }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this);
    recyclerView.setAdapter(adapter);
    reload();
  }

  private void reload () {
    int requestGeneration = ++generation;
    busy = true;
    rebuild();
    if (editable) {
      tdlib.send(new TdApi.GetBotMediaPreviewInfo(botUserId, languageCode), (info, error) ->
        runOnUiThreadOptional(() -> {
          if (requestGeneration != generation) return;
          busy = false;
          if (info != null) {
            previews = info.previews;
            languages = info.languageCodes;
          } else if (error != null) UI.showError(error);
          rebuild();
        }));
    } else {
      tdlib.send(new TdApi.GetBotMediaPreviews(botUserId), (info, error) ->
        runOnUiThreadOptional(() -> {
          if (requestGeneration != generation) return;
          busy = false;
          if (info != null) previews = info.previews;
          else if (error != null) UI.showError(error);
          rebuild();
        }));
    }
  }

  private void rebuild () {
    if (adapter == null || isDestroyed()) return;
    ArrayList<ListItem> items = new ArrayList<>();
    if (editable) {
      items.add(new ListItem(ListItem.TYPE_SETTING, LANGUAGE_ID, R.drawable.baseline_language_24,
        Lang.getString(R.string.Language) + ": " +
          (languageCode.isEmpty() ? Lang.getString(R.string.Default) : languageCode), false));
      items.add(new ListItem(ListItem.TYPE_SETTING, ADD_ID, R.drawable.baseline_add_24,
        R.string.WebAppAddPreview));
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.WebAppPreviewEditHint));
    }
    for (int i = 0; i < previews.length; i++) {
      boolean video = previews[i].content instanceof TdApi.StoryContentVideo;
      items.add(new ListItem(ListItem.TYPE_SETTING, PREVIEW_ID,
        video ? R.drawable.baseline_videocam_24 : R.drawable.baseline_image_24,
        Lang.getString(video ? R.string.Video : R.string.Photo) + " " + (i + 1), false)
        .setLongId(i));
    }
    if (previews.length == 0 || busy) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0,
        busy ? R.string.LoadingInformation : R.string.WebAppNoPreviews));
    }
    adapter.setItems(items, false);
  }

  @Override
  public void onClick (View view) {
    if (busy) return;
    if (view.getId() == LANGUAGE_ID) chooseLanguage();
    else if (view.getId() == ADD_ID) pickMedia(0);
    else if (view.getId() == PREVIEW_ID) openPreview((int) ((ListItem) view.getTag()).getLongId());
  }

  private void openPreview (int index) {
    ArrayList<MediaItem> media = new ArrayList<>();
    int selected = 0;
    for (int i = 0; i < previews.length; i++) {
      TdApi.StoryContent content = previews[i].content;
      MediaItem item = null;
      if (content instanceof TdApi.StoryContentPhoto) {
        item = MediaItem.valueOf(context, tdlib, ((TdApi.StoryContentPhoto) content).photo, null);
      } else if (content instanceof TdApi.StoryContentVideo) {
        TdApi.StoryVideo v = ((TdApi.StoryContentVideo) content).video;
        TdApi.Video video = new TdApi.Video((int) Math.ceil(v.duration), v.width, v.height,
          "preview.mp4", "video/mp4", v.hasStickers, true, v.minithumbnail, v.thumbnail, v.video);
        item = MediaItem.valueOf(context, tdlib, video, new TdApi.AlternativeVideo[0], null, null);
      }
      if (i == index) selected = media.size();
      if (item != null) media.add(item);
    }
    if (media.isEmpty() || selected >= media.size()) return;
    MediaStack stack = new MediaStack(context, tdlib);
    stack.set(selected, media);
    MediaViewController.openWithStack(this, stack, Lang.getString(R.string.WebAppPreviews), null, true);
  }

  private int fileId (TdApi.BotMediaPreview preview) {
    if (preview.content instanceof TdApi.StoryContentVideo) {
      return ((TdApi.StoryContentVideo) preview.content).video.video.id;
    }
    if (preview.content instanceof TdApi.StoryContentPhoto) {
      TdApi.PhotoSize[] sizes = ((TdApi.StoryContentPhoto) preview.content).photo.sizes;
      return sizes.length > 0 ? sizes[sizes.length - 1].photo.id : 0;
    }
    return 0;
  }

  @Override
  public boolean onLongClick (View view) {
    if (!editable || busy || view.getId() != PREVIEW_ID) return false;
    int index = (int) ((ListItem) view.getTag()).getLongId();
    int id = fileId(previews[index]);
    if (id == 0) return false;
    showOptions(null, new int[] {1, 2, 3, 4}, new String[] {
      Lang.getString(R.string.WebAppReplacePreview), Lang.getString(R.string.WebAppMovePreviewUp),
      Lang.getString(R.string.WebAppMovePreviewDown), Lang.getString(R.string.Delete)
    }, null, null, (item, action) -> {
      if (action == 1) pickMedia(id);
      else if (action == 2) movePreview(index, -1);
      else if (action == 3) movePreview(index, 1);
      else if (action == 4) {
        showOptions(Lang.getString(R.string.WebAppDeletePreviewConfirm),
          new int[] {R.id.btn_delete, R.id.btn_cancel},
          new String[] {Lang.getString(R.string.Delete), Lang.getString(R.string.Cancel)},
          new int[] {OptionColor.RED, OptionColor.NORMAL}, null, (v, choice) -> {
            if (choice == R.id.btn_delete) mutate(new TdApi.DeleteBotMediaPreviews(botUserId,
              languageCode, new int[] {id}));
            return true;
          });
      }
      return true;
    });
    return true;
  }

  private void movePreview (int index, int direction) {
    int target = index + direction;
    if (target < 0 || target >= previews.length) return;
    int[] ids = new int[previews.length];
    for (int i = 0; i < ids.length; i++) ids[i] = fileId(previews[i]);
    int swap = ids[index];
    ids[index] = ids[target];
    ids[target] = swap;
    mutate(new TdApi.ReorderBotMediaPreviews(botUserId, languageCode, ids));
  }

  private void mutate (TdApi.Function<TdApi.Ok> function) {
    busy = true;
    tdlib.send(function, (result, error) -> runOnUiThreadOptional(() -> {
      if (error != null) UI.showError(error);
      reload();
    }));
  }

  private void chooseLanguage () {
    String[] choices = new String[languages.length + 2];
    choices[0] = Lang.getString(R.string.Default);
    System.arraycopy(languages, 0, choices, 1, languages.length);
    choices[choices.length - 1] = Lang.getString(R.string.WebAppAddPreviewLanguage);
    showAlert(new AlertDialog.Builder(context, Theme.dialogTheme()).setTitle(Lang.getString(R.string.Language))
      .setItems(choices, (dialog, which) -> {
        if (which == choices.length - 1) {
          EditText input = new EditText(context);
          input.setSingleLine(true);
          input.setHint(R.string.WebAppPreviewLanguageHint);
          showAlert(new AlertDialog.Builder(context, Theme.dialogTheme()).setTitle(Lang.getString(R.string.WebAppAddPreviewLanguage))
            .setView(input).setNegativeButton(Lang.getString(R.string.Cancel), null)
            .setPositiveButton(Lang.getString(R.string.Done), (d, w) -> {
              String code = input.getText().toString().trim().toLowerCase(Locale.US);
              if (code.matches("[a-z]{2}")) {
                languageCode = code;
                previews = new TdApi.BotMediaPreview[0];
                reload();
              } else UI.showToast(R.string.WebAppPreviewLanguageHint, Toast.LENGTH_SHORT);
            }));
        } else {
          languageCode = which == 0 ? "" : languages[which - 1];
          previews = new TdApi.BotMediaPreview[0];
          reload();
        }
      }));
  }

  private void pickMedia (int replaceFileId) {
    final String targetLanguage = languageCode;
    Intent intent = new Intent(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ?
      Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
      .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
    context.putActivityResultHandler(PICK_MEDIA, (request, result, data) -> {
      context.putActivityResultHandler(PICK_MEDIA, null);
      if (!isDestroyed() && result == Activity.RESULT_OK && data != null && data.getData() != null) {
        Uri selected = data.getData();
        showAlert(new AlertDialog.Builder(context, Theme.dialogTheme()).setTitle(R.string.WebAppAddPreview)
          .setMessage(R.string.WebAppPreviewPrepareConfirm).setNegativeButton(R.string.Cancel, null)
          .setPositiveButton(R.string.WebAppContinue, (dialog, which) -> {
            if (isAlive()) upload(selected, targetLanguage, replaceFileId);
          }));
      }
    });
    try {
      context.startActivityForResult(intent, PICK_MEDIA);
    } catch (RuntimeException e) {
      context.putActivityResultHandler(PICK_MEDIA, null);
      UI.showToast(R.string.WebAppPreviewUploadError, Toast.LENGTH_SHORT);
    }
  }

  private boolean isAlive () {
    return !isDestroyed() && tdlib.isCurrent() && tdlib.isAuthorized() && tdlib.myUserId() == ownerUserId;
  }

  private void upload (Uri uri, String targetLanguage, int replaceFileId) {
    if (!isAlive()) return;
    busy = true;
    int token = ++generation;
    rebuild();
    Background.instance().post(() -> {
      File copy = null, photoFile = null;
      Bitmap photo = null;
      try {
        copy = File.createTempFile("bot-preview-source-", ".media", context.getCacheDir());
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(copy)) {
          if (in == null) throw new IllegalArgumentException("Missing media");
          byte[] buffer = new byte[65536];
          int read; long total = 0;
          while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > 64 * 1024 * 1024 || token != generation) throw new IllegalArgumentException("Media preparation cancelled");
            out.write(buffer, 0, read);
          }
        }
        photo = WebAppMediaPreparation.decodePhoto(copy);
        double duration = 0;
        if (photo != null) {
          photoFile = File.createTempFile("bot-preview-", ".jpg", context.getCacheDir());
          WebAppMediaPreparation.writePhoto(photo, photoFile);
        } else {
          MediaMetadataRetriever retriever = new MediaMetadataRetriever();
          try {
            retriever.setDataSource(copy.getAbsolutePath());
            duration = Math.min(30, Double.parseDouble(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000d);
            if (duration <= 0 || retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) == null)
              throw new IllegalArgumentException("Not a video");
          } finally { retriever.release(); }
        }
        File source = copy, normalizedPhoto = photoFile;
        final double videoDuration = duration;
        UI.post(() -> {
          if (!isAlive() || token != generation) {
            source.delete(); if (normalizedPhoto != null) normalizedPhoto.delete(); return;
          }
          if (normalizedPhoto != null) {
            source.delete();
            submitPreview(normalizedPhoto, new TdApi.InputStoryContentPhoto(new TdApi.InputFileLocal(normalizedPhoto.getAbsolutePath()), new int[0]),
              targetLanguage, replaceFileId, token);
          } else prepareVideo(source, videoDuration, targetLanguage, replaceFileId, token);
        });
      } catch (Exception e) {
        if (copy != null) copy.delete(); if (photoFile != null) photoFile.delete();
        UI.post(() -> preparationFailed(token));
      } finally { if (photo != null) photo.recycle(); }
    });
  }

  private void prepareVideo (File source, double duration, String targetLanguage, int replaceFileId, int token) {
    preparingSource = source;
    try {
      preparingOutput = File.createTempFile("bot-preview-", ".mp4", context.getCacheDir());
      preparingOutput.delete();
      File output = preparingOutput;
      transformer = WebAppMediaPreparation.prepareVideo(context, source, output, duration, success -> {
        transformer = null; preparingSource = null; preparingOutput = null; source.delete();
        if (!isAlive() || token != generation || !success) {
          output.delete(); if (!success) preparationFailed(token); return;
        }
        submitPreview(output, new TdApi.InputStoryContentVideo(new TdApi.InputFileLocal(output.getAbsolutePath()), new int[0], duration, 0, false),
          targetLanguage, replaceFileId, token);
      });
    } catch (Exception ignored) {
      clearPreparation(); preparationFailed(token);
    }
  }

  private void submitPreview (File uploadedFile, TdApi.InputStoryContent content, String targetLanguage, int replaceFileId, int token) {
    if (!isAlive() || token != generation) { uploadedFile.delete(); return; }
    TdApi.Function<TdApi.BotMediaPreview> function = replaceFileId != 0 ?
      new TdApi.EditBotMediaPreview(botUserId, targetLanguage, replaceFileId, content) :
      new TdApi.AddBotMediaPreview(botUserId, targetLanguage, content);
    tdlib.send(function, (preview, error) -> {
      // These functions finish after the preview is stored server-side, unlike PostStory.
      uploadedFile.delete();
      UI.post(() -> {
        if (!isAlive() || token != generation) return;
        if (error != null) UI.showError(error);
        reload();
      });
    });
  }

  private void preparationFailed (int token) {
    if (!isAlive() || token != generation) return;
    busy = false; rebuild(); UI.showToast(R.string.WebAppPreviewUploadError, Toast.LENGTH_LONG);
  }

  private void clearPreparation () {
    if (transformer != null) { transformer.cancel(); transformer = null; }
    if (preparingSource != null) { preparingSource.delete(); preparingSource = null; }
    if (preparingOutput != null) { preparingOutput.delete(); preparingOutput = null; }
  }

  @Override
  public void destroy () {
    generation++;
    clearPreparation();
    context.putActivityResultHandler(PICK_MEDIA, null);
    super.destroy();
  }
}
