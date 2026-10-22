/*
 * This file is a part of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.ui;

import android.content.Intent;
import android.graphics.Canvas;
import android.net.Uri;
import android.view.View;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TGMessage;
import org.thunderdog.challegram.loader.ImageFileMap;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.DrawModifier;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSets;
import org.thunderdog.challegram.util.text.TextWrapper;

import java.util.List;

public final class BusinessLocationUi {
  private static final long MAP_RECEIVER = 0x425553;

  private BusinessLocationUi () { }

  public static ListItem newItem () {
    return new ListItem(ListItem.TYPE_INFO_MULTILINE, R.id.btn_businessLocation,
      R.drawable.baseline_location_on_24, R.string.Location);
  }

  public static void bind (SettingView view, TdApi.BusinessLocation location) {
    view.setName(R.string.Location);
    view.setText(addressText(location));
    if (location != null && location.location != null) {
      ImageReceiver receiver = view.getComplexReceiver().getImageReceiver(MAP_RECEIVER);
      receiver.setRadius(Screen.dp(4));
      receiver.requestFile(new ImageFileMap(location.location.latitude, location.location.longitude,
        15, Screen.dp(44), Screen.dp(44)));
      view.addDrawModifier(new MapPreviewModifier(receiver), false);
    }
  }

  public static TextWrapper addressText (TdApi.BusinessLocation location) {
    return new TextWrapper(location != null ? location.address : "",
      TGMessage.simpleTextStyleProvider(), TextColorSets.Regular.NORMAL)
      .addTextFlags(Lang.rtl() ? Text.FLAG_ALIGN_RIGHT : 0);
  }

  public static void clearPreview (SettingView view) {
    List<DrawModifier> modifiers = view.getDrawModifiers();
    if (modifiers == null) return;
    for (int i = modifiers.size() - 1; i >= 0; i--) {
      DrawModifier modifier = modifiers.get(i);
      if (modifier instanceof MapPreviewModifier) {
        ((MapPreviewModifier) modifier).receiver.clear();
        view.removeDrawModifier(modifier);
      }
    }
  }

  private static final class MapPreviewModifier implements DrawModifier {
    private final ImageReceiver receiver;

    MapPreviewModifier (ImageReceiver receiver) {
      this.receiver = receiver;
    }

    @Override
    public int getWidth () { return Screen.dp(56); }

    @Override
    public void afterDraw (View target, Canvas canvas) {
      int size = Screen.dp(44);
      int x = Lang.rtl() ? Screen.dp(16) : target.getMeasuredWidth() - size - Screen.dp(16);
      int y = (target.getMeasuredHeight() - size) / 2;
      receiver.setBounds(x, y, x + size, y + size);
      if (receiver.needPlaceholder()) receiver.drawPlaceholder(canvas);
      receiver.draw(canvas);
    }
  }

  public static void open (ViewController<?> parent, TdApi.BusinessLocation location,
                           boolean inMapsApp) {
    if (location == null) return;
    if (location.location != null && !inMapsApp) {
      MapController.Args args = new MapController.Args(location.location.latitude,
        location.location.longitude);
      args.title = location.address;
      parent.tdlib().ui().openMap(parent, args);
    } else {
      UI.startActivity(new Intent(Intent.ACTION_VIEW,
        Uri.parse("https://maps.google.com/maps").buildUpon()
          .appendQueryParameter("q", location.address).build()));
    }
  }

  public static void showOptions (ViewController<?> parent, TdApi.BusinessLocation location,
                                  boolean ownProfile) {
    if (location == null) return;
    ViewController.Options.Builder options = new ViewController.Options.Builder();
    options.info(location.address);
    if (ownProfile) {
      if (location.location != null) {
        options.item(new ViewController.OptionItem(R.id.btn_open,
          Lang.getString(R.string.ProfileLocationView), ViewController.OptionColor.NORMAL,
          R.drawable.baseline_location_on_24));
      }
      options.item(new ViewController.OptionItem(R.id.btn_map,
        Lang.getString(R.string.ProfileLocationMaps), ViewController.OptionColor.NORMAL,
        R.drawable.baseline_map_24));
    }
    options.item(new ViewController.OptionItem(R.id.btn_copyText,
      Lang.getString(ownProfile ? R.string.ProfileLocationCopy : R.string.Copy),
      ViewController.OptionColor.NORMAL, R.drawable.baseline_content_copy_24));
    if (ownProfile) {
      options.item(new ViewController.OptionItem(R.id.btn_edit,
        Lang.getString(R.string.ProfileLocationEdit), ViewController.OptionColor.NORMAL,
        R.drawable.baseline_edit_24));
      options.item(new ViewController.OptionItem(R.id.btn_delete,
        Lang.getString(R.string.Remove), ViewController.OptionColor.RED,
        R.drawable.baseline_delete_24));
    }
    parent.showOptions(options.build(), (view, id) -> {
      if (id == R.id.btn_copyText) {
        UI.copyText(location.address, R.string.BusinessLocationCopied);
      } else if (id == R.id.btn_open || id == R.id.btn_map) {
        open(parent, location, id == R.id.btn_map);
      } else if (id == R.id.btn_edit) {
        EditBusinessLocationController.open(parent);
      } else if (id == R.id.btn_delete) {
        confirmRemove(parent, null);
      }
      return true;
    });
  }

  public static void confirmRemove (ViewController<?> parent, Runnable onRemoved) {
    parent.showConfirm(Lang.getString(R.string.BusinessLocationClearMessage),
      Lang.getString(R.string.BusinessLocationClear), R.drawable.baseline_delete_24,
      ViewController.OptionColor.RED, () -> parent.tdlib().send(new TdApi.SetBusinessLocation(null),
        (ok, error) -> parent.tdlib().uiExecute(() -> {
          if (error != null) UI.showError(error);
          else if (!parent.isDestroyed() && onRemoved != null) onRemoved.run();
        })));
  }
}
