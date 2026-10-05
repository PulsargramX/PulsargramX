/*
 * This file is a part of Pulsargram X, based on Telegram X.
 * Copyright © 2026 Pulsargram X contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See <https://www.gnu.org/licenses/> for the GNU General Public License.
 */
package org.thunderdog.challegram.component.webapp;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.view.HapticFeedbackConstants;
import android.webkit.URLUtil;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.ui.camera.CameraController;
import org.thunderdog.challegram.core.Lang;

import java.util.ArrayList;
import java.util.List;

import static org.thunderdog.challegram.component.webapp.WebAppDevice.object;

/** Browser-owned prompts are bounded, gesture gated, and invalidated with their document. */
public final class WebAppBrowserActions {
  private final WebAppActions.Host host;
  private AlertDialog dialog;
  private boolean scannerOpen;
  private BroadcastReceiver shortcutReceiver;
  private PendingIntent shortcutCallback;
  private boolean checkingDownload;

  public WebAppBrowserActions (WebAppActions.Host host) { this.host = host; }

  public boolean handle (String event, JSONObject data) {
    switch (event) {
      case "web_app_open_link":
        if (host.hasRecentUserGesture()) host.openExternalUrl(data.optString("url"));
        return true;
      case "web_app_open_tg_link": {
        String path = data.optString("path_full");
        if (host.hasRecentUserGesture() && path.startsWith("/") && !path.startsWith("//")) {
          host.openExternalUrl("https://t.me" + path);
        }
        return true;
      }
      case "web_app_open_popup": popup(data); return true;
      case "web_app_read_text_from_clipboard": clipboard(data); return true;
      case "web_app_trigger_haptic_feedback":
        if (host.hasRecentUserGesture() && host.webView() != null) {
          String type = data.optString("type");
          host.webView().performHapticFeedback("selection_change".equals(type) ?
            HapticFeedbackConstants.CLOCK_TICK : "notification".equals(type) ?
              HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.VIRTUAL_KEY);
        }
        return true;
      case "web_app_request_file_download": download(data); return true;
      case "web_app_add_to_home_screen": shortcut(); return true;
      case "web_app_check_home_screen":
        checkShortcut();
        return true;
      case "web_app_open_scan_qr_popup":
        openScanner();
        return true;
      case "web_app_close_scan_qr_popup": closeScanner(); return true;
      default: return false;
    }
  }

  private void popup (JSONObject data) {
    if (dialog != null || !host.hasRecentUserGesture()) {
      host.emit("popup_closed", new JSONObject());
      return;
    }
    String title = data.optString("title");
    String message = data.optString("message");
    JSONArray buttons = data.optJSONArray("buttons");
    if (title.length() > 64 || message.isEmpty() || message.length() > 256 ||
        buttons == null || buttons.length() == 0 || buttons.length() > 3) {
      host.emit("popup_closed", new JSONObject());
      return;
    }
    List<String> texts = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < buttons.length(); i++) {
      JSONObject button = buttons.optJSONObject(i);
      if (button == null) { host.emit("popup_closed", new JSONObject()); return; }
      String type = button.optString("type", "default");
      String label = button.optString("text");
      if (type.equals("ok")) label = host.context().getString(android.R.string.ok);
      else if (type.equals("close")) label = Lang.getString(R.string.WebAppRuntimeClose);
      else if (type.equals("cancel")) label = Lang.getString(R.string.WebAppRuntimeCancel);
      if (label.isEmpty() || label.length() > 64 || button.optString("id").length() > 64) {
        host.emit("popup_closed", new JSONObject()); return;
      }
      texts.add(label);
      ids.add(button.optString("id"));
    }
    AlertDialog.Builder builder = new AlertDialog.Builder(host.context()).setTitle(title).setMessage(message);
    android.content.DialogInterface.OnClickListener click = (d, which) -> {
      int index = which == AlertDialog.BUTTON_POSITIVE ? 0 : which == AlertDialog.BUTTON_NEGATIVE ? 1 : 2;
      if (host.isAlive()) {
        host.onUserGesture();
        d.dismiss();
        host.emit("popup_closed", object("button_id", ids.get(index)));
      }
    };
    builder.setPositiveButton(texts.get(0), click);
    if (texts.size() > 1) builder.setNegativeButton(texts.get(1), click);
    if (texts.size() > 2) builder.setNeutralButton(texts.get(2), click);
    dialog = builder.create();
    dialog.setOnCancelListener(d -> { if (host.isAlive()) host.emit("popup_closed", new JSONObject()); });
    show();
  }

  private void clipboard (JSONObject data) {
    String request = data.optString("req_id");
    if (request.length() > 256) return;
    if (!host.hasRecentUserGesture() || dialog != null) {
      host.emit("clipboard_text_received", object("req_id", request)); return;
    }
    dialog = new AlertDialog.Builder(host.context()).setTitle(R.string.WebAppRuntimeClipboardTitle)
      .setMessage(R.string.WebAppRuntimeClipboardMessage)
      .setPositiveButton(R.string.WebAppRuntimeAllow, (d, which) -> {
        if (!host.isAlive()) return;
        ClipboardManager clipboard = (ClipboardManager) host.context().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
        host.emit("clipboard_text_received", object("req_id", request,
          "data", text == null ? "" : text.subSequence(0, Math.min(text.length(), 65536)).toString()));
      }).setNegativeButton(R.string.WebAppRuntimeCancel,
        (d, which) -> host.emit("clipboard_text_received", object("req_id", request))).create();
    dialog.setOnCancelListener(d -> host.emit("clipboard_text_received", object("req_id", request)));
    show();
  }

  private void download (JSONObject data) {
    String url = data.optString("url");
    Uri uri = Uri.parse(url);
    if (!host.hasRecentUserGesture() || dialog != null || checkingDownload || WebAppOrigin.origin(url) == null ||
        !"https".equalsIgnoreCase(uri.getScheme())) {
      host.emit("file_download_requested", object("status", "cancelled")); return;
    }
    String name = data.optString("file_name", URLUtil.guessFileName(url, null, null));
    name = name.replaceAll("[\\\\/\\p{Cntrl}]", "_");
    if (name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 255) {
      host.emit("file_download_requested", object("status", "cancelled")); return;
    }
    final String fileName = name;
    checkingDownload = true;
    host.tdlib().send(new TdApi.CheckWebAppFileDownload(host.session().request.botUserId, fileName, url),
      (result, error) -> host.runOnUiThread(() -> {
        checkingDownload = false;
        if (!host.isAlive()) return;
        if (error != null || dialog != null) {
          host.emit("file_download_requested", object("status", "cancelled"));
          return;
        }
        dialog = new AlertDialog.Builder(host.context()).setTitle(R.string.WebAppRuntimeDownloadTitle)
          .setMessage(fileName + "\n" + uri.getHost())
          .setPositiveButton(R.string.WebAppRuntimeDownload, (d, which) -> {
            if (!host.isAlive()) return;
            if (Build.VERSION.SDK_INT < 29 && androidx.core.content.ContextCompat.checkSelfPermission(host.context(),
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
              ((BaseActivity) host.activity()).requestCustomPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                (code, permissions, results, count) -> {
                  if (!host.isAlive()) return;
                  if (androidx.core.content.ContextCompat.checkSelfPermission(host.context(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                      android.content.pm.PackageManager.PERMISSION_GRANTED) enqueueDownload(uri, fileName);
                  else host.emit("file_download_requested", object("status", "cancelled"));
                });
            } else enqueueDownload(uri, fileName);
          }).setNegativeButton(R.string.WebAppRuntimeCancel,
            (d, which) -> host.emit("file_download_requested", object("status", "cancelled"))).create();
        dialog.setOnCancelListener(d -> host.emit("file_download_requested", object("status", "cancelled")));
        show();
      }));
  }

  private void enqueueDownload (Uri uri, String name) {
    if (!host.isAlive()) return;
    try {
      DownloadManager manager = (DownloadManager) host.context().getSystemService(Context.DOWNLOAD_SERVICE);
      DownloadManager.Request request = new DownloadManager.Request(uri).setTitle(name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
      long id = manager.enqueue(request);
      host.emit("file_download_requested", object("status", id >= 0 ? "downloading" : "cancelled"));
    } catch (RuntimeException ignored) {
      host.emit("file_download_requested", object("status", "cancelled"));
    }
  }

  private void shortcut () {
    String original = host.session().request.publicLaunchUrl(host.tdlib());
    if (!host.hasRecentUserGesture() || original == null || WebAppOrigin.origin(original) == null ||
        !ShortcutManagerCompat.isRequestPinShortcutSupported(host.context())) {
      host.emit("home_screen_failed", object("error", "UNSUPPORTED")); return;
    }
    String label = host.session().request.botUsername;
    if (label == null || label.isEmpty()) label = Lang.getString(R.string.WebAppRuntimeTitle);
    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(original)).setPackage(host.context().getPackageName())
      .putExtra("account_id", host.tdlib().id());
    ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(host.context(),
      WebAppDevice.namespace(host.tdlib(), host.session().request.botUserId))
      .setShortLabel(label).setIntent(intent)
      .setIcon(IconCompat.createWithResource(host.context(), R.drawable.baseline_public_24)).build();
    try {
      clearShortcutCallback();
      String action = host.context().getPackageName() + ".MINI_APP_PINNED_" + java.util.UUID.randomUUID();
      shortcutReceiver = new BroadcastReceiver() {
        @Override public void onReceive (Context context, Intent intent) {
          if (!action.equals(intent.getAction())) return;
          clearShortcutCallback();
          if (host.isAlive()) host.emit("home_screen_added", null);
        }
      };
      androidx.core.content.ContextCompat.registerReceiver(host.context(), shortcutReceiver,
        new IntentFilter(action), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
      Intent callback = new Intent(action).setPackage(host.context().getPackageName());
      int flags = PendingIntent.FLAG_UPDATE_CURRENT;
      if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
      shortcutCallback = PendingIntent.getBroadcast(host.context(), 0, callback, flags);
      if (!ShortcutManagerCompat.requestPinShortcut(host.context(), shortcut, shortcutCallback.getIntentSender())) {
        clearShortcutCallback();
        host.emit("home_screen_failed", object("error", "UNSUPPORTED"));
      }
    } catch (RuntimeException ignored) {
      clearShortcutCallback();
      host.emit("home_screen_failed", object("error", "UNSUPPORTED"));
    }
  }

  private void checkShortcut () {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(host.context())) {
      host.emit("home_screen_checked", object("status", "unsupported"));
      return;
    }
    String id = WebAppDevice.namespace(host.tdlib(), host.session().request.botUserId);
    try {
      for (ShortcutInfoCompat shortcut : ShortcutManagerCompat.getShortcuts(host.context(), ShortcutManagerCompat.FLAG_MATCH_PINNED)) {
        if (id.equals(shortcut.getId())) {
          host.emit("home_screen_checked", object("status", "added"));
          return;
        }
      }
      host.emit("home_screen_checked", object("status", Build.VERSION.SDK_INT >= 26 ? "missed" : "unknown"));
    } catch (RuntimeException ignored) {
      host.emit("home_screen_checked", object("status", "unknown"));
    }
  }

  private void clearShortcutCallback () {
    if (shortcutReceiver != null) {
      try { host.context().unregisterReceiver(shortcutReceiver); } catch (RuntimeException ignored) { }
      shortcutReceiver = null;
    }
    if (shortcutCallback != null) { shortcutCallback.cancel(); shortcutCallback = null; }
  }


  private void openScanner () {
    if (scannerOpen || !host.hasRecentUserGesture()) {
      if (!scannerOpen) host.emit("scan_qr_popup_closed", null);
      return;
    }
    BaseActivity activity = (BaseActivity) host.activity();
    activity.requestCustomPermissions(new String[] {android.Manifest.permission.CAMERA}, (code, permissions, grants, count) -> {
      if (!host.isAlive()) return;
      if (androidx.core.content.ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) !=
          android.content.pm.PackageManager.PERMISSION_GRANTED) {
        host.emit("scan_qr_popup_closed", null);
        return;
      }
      scannerOpen = true;
      host.controller().openInAppCamera(new ViewController.CameraOpenOptions()
        .ignoreAnchor(true).noTrace(true).allowSystem(false).optionalMicrophone(true)
        .qrModeSubtitle(R.string.WebAppRuntimeScanQr).mode(CameraController.MODE_QR)
        .qrCodeListener(new CameraController.QrCodeListener() {
          @Override public boolean acceptArbitraryCodes () { return true; }
          @Override public boolean closeAfterScan () { return false; }
          @Override public void onQrCodeScanned (String value) {
            if (scannerOpen && host.isAlive() && value.length() <= 65536) {
              host.emit("qr_text_received", object("data", value));
            }
          }
          @Override public void onScannerClosed () {
            if (scannerOpen) {
              scannerOpen = false;
              host.emit("scan_qr_popup_closed", null);
            }
          }
        }));
    });
  }

  private void closeScanner () {
    if (scannerOpen) {
      scannerOpen = false;
      ((BaseActivity) host.activity()).forceCloseCamera();
      host.emit("scan_qr_popup_closed", null);
    }
  }

  private void show () {
    dialog.setOnDismissListener(d -> dialog = null);
    dialog.show();
  }

  public void destroy () {
    clearShortcutCallback();
    closeScanner();
    if (dialog != null) { dialog.setOnCancelListener(null); dialog.dismiss(); dialog = null; }
  }
}
