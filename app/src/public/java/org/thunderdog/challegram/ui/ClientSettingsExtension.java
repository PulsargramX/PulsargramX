package org.thunderdog.challegram.ui;

import android.view.View;

import org.thunderdog.challegram.component.base.SettingView;

import java.util.ArrayList;

public final class ClientSettingsExtension {
  private ClientSettingsExtension () { }

  public static void appendSettings (ArrayList<ListItem> items) { }

  public static boolean bindSetting (ListItem item, SettingView view, boolean isUpdate) {
    return false;
  }

  public static boolean handleClick (View view, SettingsAdapter adapter) { return false; }
}
