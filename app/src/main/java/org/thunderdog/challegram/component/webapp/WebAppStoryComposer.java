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

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.media3.transformer.Transformer;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;
import org.thunderdog.challegram.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A native review and privacy step is always required before posting a Mini App story. */
final class WebAppStoryComposer {
  private final WebAppActions actions;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private volatile HttpURLConnection download;
  private volatile int generation;
  private boolean busy, posting;
  private File source, ready;
  private Transformer transformer;
  private AlertDialog dialog;
  private VideoView videoView;
  private long ownChatId;
  private WebAppStoryUpload upload;

  WebAppStoryComposer (WebAppActions actions) { this.actions = actions; }

  void open (JSONObject data) {
    if (busy || !actions.alive()) return;
    String url = data.optString("media_url");
    if (!https(url)) { failed(); return; }
    busy = true;
    int token = ++generation;
    actions.send(new TdApi.CreatePrivateChat(actions.host.tdlib().myUserId(), false), result -> {
      if (token != generation) return;
      if (!(result instanceof TdApi.Chat)) { failed(); return; }
      ownChatId = ((TdApi.Chat) result).id;
      actions.send(new TdApi.CanPostStory(ownChatId), allowed -> {
        if (token != generation) return;
        if (!(allowed instanceof TdApi.CanPostStoryResultOk)) {
          actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppStoryTitle)
            .setMessage(allowed instanceof TdApi.CanPostStoryResultPremiumNeeded ? R.string.WebAppStoryPremiumRequired : R.string.WebAppStoryUnavailable)
            .setPositiveButton(android.R.string.ok, null), null);
          cleanup(); return;
        }
        dialog = actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppStoryTitle)
          .setMessage(R.string.WebAppPreparingStory).setNegativeButton(android.R.string.cancel, (d, w) -> cleanup()), this::cleanup);
        worker.execute(() -> {
          File file = download(url, token);
          org.thunderdog.challegram.tool.UI.post(() -> {
            if (!actions.alive() || token != generation) { if (file != null) file.delete(); return; }
            if (file == null) { failed(); return; }
            source = file; inspect(data, token);
          });
        });
      });
    });
  }

  private static boolean https (String value) {
    Uri uri = Uri.parse(value);
    return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
  }
  private File download (String value, int token) {
    File file = null;
    try {
      for (int redirects = 0; redirects < 5; redirects++) {
        if (token != generation || !https(value)) return null;
        URL url = new URL(value);
        for (InetAddress address : InetAddress.getAllByName(url.getHost())) {
          if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() ||
              address.isSiteLocalAddress() || address.isMulticastAddress()) return null;
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(); download = connection;
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(false);
        try {
          int code = connection.getResponseCode();
          if (code >= 300 && code < 400) {
            String location = connection.getHeaderField("Location");
            if (location == null) return null;
            value = new URL(url, location).toString(); continue;
          }
          if (code != 200 || connection.getContentLength() > 64 * 1024 * 1024) return null;
          file = File.createTempFile("miniapp-story-", ".media", actions.host.context().getCacheDir());
          try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file)) {
            byte[] bytes = new byte[32768]; int count; long total = 0;
            while ((count = input.read(bytes)) != -1) {
              total += count;
              if (token != generation || total > 64 * 1024 * 1024) throw new java.io.IOException();
              output.write(bytes, 0, count);
            }
          }
          return file;
        } finally { connection.disconnect(); download = null; }
      }
    } catch (Exception ignored) { if (file != null) file.delete(); }
    return null;
  }

  private void inspect (JSONObject data, int token) {
    worker.execute(() -> {
      Bitmap photo = null;
      double duration = 0;
      try {
        photo = WebAppMediaPreparation.decodePhoto(source);
        if (photo == null) {
          MediaMetadataRetriever media = new MediaMetadataRetriever();
          try {
            media.setDataSource(source.getAbsolutePath());
            duration = Long.parseLong(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0;
            if (duration > 0) duration = Math.min(60, duration);
          } finally { media.release(); }
        }
      } catch (Exception ignored) { }
      Bitmap preview = photo; double length = duration;
      org.thunderdog.challegram.tool.UI.post(() -> {
        if (!actions.alive() || token != generation) { if (preview != null) preview.recycle(); return; }
        if (preview == null && length <= 0) { failed(); return; }
        review(data, preview, length);
      });
    });
  }

  private void review (JSONObject data, Bitmap photo, double duration) {
    if (dialog != null) dialog.dismiss();
    LinearLayout layout = actions.column();
    int height = Math.round(280 * actions.host.context().getResources().getDisplayMetrics().density);
    if (photo != null) {
      ImageView preview = new ImageView(actions.host.activity()); preview.setImageBitmap(photo); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
      layout.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
    } else {
      videoView = new VideoView(actions.host.activity()); videoView.setVideoPath(source.getAbsolutePath());
      videoView.setOnPreparedListener(player -> { player.setLooping(true); videoView.start(); });
      layout.addView(videoView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
    }
    EditText caption = actions.field(layout, R.string.WebAppStoryCaption, data.optString("text")); caption.setSingleLine(false);
    Spinner privacy = new Spinner(actions.host.activity());
    privacy.setAdapter(new ArrayAdapter<>(actions.host.activity(), android.R.layout.simple_spinner_dropdown_item,
      new String[] {actions.host.context().getString(R.string.WebAppStoryContacts), actions.host.context().getString(R.string.WebAppStoryCloseFriends),
        actions.host.context().getString(R.string.WebAppStoryEveryone)}));
    layout.addView(privacy);
    CheckBox keep = new CheckBox(actions.host.activity()); keep.setText(R.string.WebAppStoryKeep); layout.addView(keep);
    CheckBox protect = new CheckBox(actions.host.activity()); protect.setText(R.string.WebAppStoryProtect); layout.addView(protect);
    JSONObject link = data.optJSONObject("widget_link");
    String linkUrl = link == null ? "" : link.optString("url");
    CheckBox includeLink = new CheckBox(actions.host.activity());
    TdApi.User me = actions.host.tdlib().myUser();
    if (!linkUrl.isEmpty() && https(linkUrl) && me != null && me.isPremium) {
      includeLink.setText(actions.host.context().getString(R.string.WebAppStoryLink, linkUrl)); includeLink.setChecked(true); layout.addView(includeLink);
    }
    TextView disclosure = new TextView(actions.host.activity()); disclosure.setText(R.string.WebAppStoryDisclosure); layout.addView(disclosure);
    ScrollView scroll = new ScrollView(actions.host.activity()); scroll.addView(layout);
    dialog = actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppStoryTitle).setView(scroll)
      .setPositiveButton(R.string.WebAppStoryPost, null).setNegativeButton(android.R.string.cancel, (d, w) -> { if (photo != null) photo.recycle(); cleanup(); }),
      () -> { if (photo != null) photo.recycle(); cleanup(); });
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      if (!actions.alive()) return;
      String text = caption.getText().toString();
      TdApi.User current = actions.host.tdlib().myUser();
      int maxCaption = current != null && current.isPremium ? 2048 : 200;
      if (text.codePointCount(0, text.length()) > maxCaption) { caption.setError(actions.host.context().getString(R.string.WebAppStoryCaptionLong)); return; }
      TdApi.StoryPrivacySettings settings = privacy.getSelectedItemPosition() == 0 ? new TdApi.StoryPrivacySettingsContacts(new long[0]) :
        privacy.getSelectedItemPosition() == 1 ? new TdApi.StoryPrivacySettingsCloseFriends() : new TdApi.StoryPrivacySettingsEveryone(new long[0]);
      TdApi.InputStoryAreas areas = includeLink.isChecked() ? new TdApi.InputStoryAreas(new TdApi.InputStoryArea[] {
        new TdApi.InputStoryArea(new TdApi.StoryAreaPosition(50, 80, 70, 8, 0, 10), new TdApi.InputStoryAreaTypeLink(linkUrl))}) : null;
      boolean isKept = keep.isChecked(), isProtected = protect.isChecked();
      dialog.dismiss(); stopVideo();
      dialog = actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppStoryTitle)
        .setMessage(R.string.WebAppPreparingStory).setNegativeButton(android.R.string.cancel, (d, w) -> cleanup()), this::cleanup);
      int token = generation;
      if (photo != null) {
        worker.execute(() -> {
          File output = null;
          try {
            output = File.createTempFile("miniapp-story-", ".jpg", actions.host.context().getCacheDir());
            WebAppMediaPreparation.writePhoto(photo, output);
          } catch (Exception ignored) { if (output != null) output.delete(); output = null; }
          finally { photo.recycle(); }
          File prepared = output;
          org.thunderdog.challegram.tool.UI.post(() -> {
            if (!actions.alive() || token != generation) { if (prepared != null) prepared.delete(); return; }
            if (prepared == null) { failed(); return; }
            ready = prepared;
            post(new TdApi.InputStoryContentPhoto(new TdApi.InputFileLocal(ready.getAbsolutePath()), new int[0]), text, settings, areas, isKept, isProtected);
          });
        });
      } else encodeVideo(duration, token, content -> post(content, text, settings, areas, isKept, isProtected));
    });
  }

  private void encodeVideo (double duration, int token, java.util.function.Consumer<TdApi.InputStoryContent> completed) {
    try {
      ready = File.createTempFile("miniapp-story-", ".mp4", actions.host.context().getCacheDir()); ready.delete();
      transformer = WebAppMediaPreparation.prepareVideo(actions.host.context(), source, ready, duration, success -> {
        transformer = null;
        if (!actions.alive() || token != generation) return;
        if (success) completed.accept(new TdApi.InputStoryContentVideo(new TdApi.InputFileLocal(ready.getAbsolutePath()), new int[0], duration, 0, false));
        else failed();
      });
    } catch (Exception ignored) { failed(); }
  }
  private void post (TdApi.InputStoryContent content, String caption, TdApi.StoryPrivacySettings privacy,
                     TdApi.InputStoryAreas areas, boolean keep, boolean protect) {
    if (!actions.alive()) { cleanup(); return; }
    posting = true;
    if (dialog != null) { dialog.dismiss(); dialog = null; }
    if (source != null) { source.delete(); source = null; }
    File uploadedFile = ready; ready = null;
    TdApi.PostStory request = new TdApi.PostStory(ownChatId, content, areas, new TdApi.FormattedText(caption, null),
      privacy, new int[0], 86400, null, keep, protect);
    upload = new WebAppStoryUpload(actions.host.tdlib(), request, uploadedFile, success -> {
      posting = false; upload = null;
      if (actions.alive()) Toast.makeText(actions.host.context(), success ? R.string.WebAppStoryPosted : R.string.WebAppStoryFailed, Toast.LENGTH_LONG).show();
      cleanup();
    });
  }
  private void failed () {
    if (actions.alive()) Toast.makeText(actions.host.context(), R.string.WebAppStoryFailed, Toast.LENGTH_LONG).show();
    cleanup();
  }
  private void stopVideo () { if (videoView != null) { videoView.stopPlayback(); videoView = null; } }
  private void cleanup () {
    generation++; busy = posting; stopVideo();
    HttpURLConnection connection = download; if (connection != null) connection.disconnect();
    if (transformer != null) { transformer.cancel(); transformer = null; }
    if (dialog != null) { dialog.dismiss(); dialog = null; }
    if (!posting) {
      if (source != null) { source.delete(); source = null; }
      if (ready != null) { ready.delete(); ready = null; }
    }
  }
  void destroy () {
    if (upload != null) { upload.detach(); upload = null; }
    posting = false; cleanup(); worker.shutdownNow();
  }
}
