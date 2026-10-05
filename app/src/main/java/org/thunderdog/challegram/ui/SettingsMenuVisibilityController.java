package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.DrawerController;
import org.thunderdog.challegram.navigation.NavigationStack;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;

import org.thunderdog.challegram.unsorted.Settings;

public class SettingsMenuVisibilityController extends RecyclerViewController<Void> implements View.OnClickListener {

  public SettingsMenuVisibilityController(Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId() {
    return R.id.controller_menuVisibility;
  }

  @Override
  public CharSequence getName() {
    return Lang.getString(R.string.MenuVisibility);
  }

  private SettingsAdapter adapter;

  @Override
  protected void onCreateView(Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting(ListItem item, SettingView view, boolean isUpdate) {
        final int itemId = item.getId();
        if (itemId == R.id.btn_showContacts) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowContacts(), isUpdate);
        } else if (itemId == R.id.btn_showCalls) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowCalls(), isUpdate);
        } else if (itemId == R.id.btn_showSavedMessages) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowSavedMessages(), isUpdate);
        } else if (itemId == R.id.btn_showMiniApps) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowMiniApps(), isUpdate);
        } else if (itemId == R.id.btn_showHelp) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowHelp(), isUpdate);
        } else if (itemId == R.id.btn_showInviteFriends) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowInviteFriends(), isUpdate);
        } else if (itemId == R.id.btn_showNightMode) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowNightMode(), isUpdate);
        } else if (itemId == R.id.btn_showAskQuestion) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowAskQuestion(), isUpdate);
        } else if (itemId == R.id.btn_showFAQ) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowFAQ(), isUpdate);
        } else if (itemId == R.id.btn_showPrivacyPolicy) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowPrivacyPolicy(), isUpdate);
        } else if (itemId == R.id.btn_showCheckUpdates) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowCheckUpdates(), isUpdate);
        } else if (itemId == R.id.btn_showSourceCode) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowSourceCode(), isUpdate);
        } else if (itemId == R.id.btn_showSourceCodeChanges) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowSourceCodeChanges(), isUpdate);
        } else if (itemId == R.id.btn_showCopyReport) {
          view.getToggler().setRadioEnabled(Settings.instance().getShowCopyReport(), isUpdate);
        }
      }
    };

    ArrayList<ListItem> items = new ArrayList<>();
    
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.DrawerMenu));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showContacts, R.drawable.baseline_person_24, R.string.Contacts));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showCalls, R.drawable.baseline_call_24, R.string.Calls));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showSavedMessages, R.drawable.baseline_bookmark_24, R.string.SavedMessages));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showMiniApps,
      R.drawable.deproko_baseline_bots_24, R.string.WebAppBrowseApps));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showHelp, R.drawable.baseline_help_24, R.string.Help));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showInviteFriends, R.drawable.baseline_person_add_24, R.string.InviteFriends));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showNightMode, R.drawable.baseline_brightness_2_24, R.string.NightMode));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.SettingsPage));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showAskQuestion, R.drawable.baseline_live_help_24, R.string.AskAQuestion));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showFAQ, R.drawable.baseline_help_24, R.string.TelegramFAQ));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showPrivacyPolicy, R.drawable.baseline_policy_24, R.string.PrivacyPolicy));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showCheckUpdates, R.drawable.baseline_update_24, R.string.CheckForUpdates));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showSourceCode, R.drawable.baseline_github_24, R.string.ViewSourceCode));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showSourceCodeChanges, R.drawable.baseline_code_24, R.string.ViewSourceCodeChanges));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showCopyReport, R.drawable.baseline_bug_report_24, R.string.CopyReportData));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    adapter.setItems(items, false);
    recyclerView.setAdapter(adapter);
  }

  @Override
  public void onClick(View v) {
    final int viewId = v.getId();
    boolean drawerChanged = false;
    boolean settingsChanged = false;
    if (viewId == R.id.btn_showContacts) {
      Settings.instance().setShowContacts(adapter.toggleView(v));
      drawerChanged = true;
    } else if (viewId == R.id.btn_showCalls) {
      Settings.instance().setShowCalls(adapter.toggleView(v));
      drawerChanged = true;
    } else if (viewId == R.id.btn_showSavedMessages) {
      Settings.instance().setShowSavedMessages(adapter.toggleView(v));
      drawerChanged = true;
    } else if (viewId == R.id.btn_showMiniApps) {
      Settings.instance().setShowMiniApps(adapter.toggleView(v));
      drawerChanged = true;
    } else if (viewId == R.id.btn_showHelp) {
      Settings.instance().setShowHelp(adapter.toggleView(v));
      drawerChanged = true;
      settingsChanged = true;
    } else if (viewId == R.id.btn_showInviteFriends) {
      Settings.instance().setShowInviteFriends(adapter.toggleView(v));
      drawerChanged = true;
    } else if (viewId == R.id.btn_showNightMode) {
      Settings.instance().setShowNightMode(adapter.toggleView(v));
      drawerChanged = true;
    } else if (viewId == R.id.btn_showAskQuestion) {
      Settings.instance().setShowAskQuestion(adapter.toggleView(v));
      settingsChanged = true;
    } else if (viewId == R.id.btn_showFAQ) {
      Settings.instance().setShowFAQ(adapter.toggleView(v));
      settingsChanged = true;
    } else if (viewId == R.id.btn_showPrivacyPolicy) {
      Settings.instance().setShowPrivacyPolicy(adapter.toggleView(v));
      settingsChanged = true;
    } else if (viewId == R.id.btn_showCheckUpdates) {
      Settings.instance().setShowCheckUpdates(adapter.toggleView(v));
      settingsChanged = true;
    } else if (viewId == R.id.btn_showSourceCode) {
      Settings.instance().setShowSourceCode(adapter.toggleView(v));
      settingsChanged = true;
    } else if (viewId == R.id.btn_showSourceCodeChanges) {
      Settings.instance().setShowSourceCodeChanges(adapter.toggleView(v));
      settingsChanged = true;
    } else if (viewId == R.id.btn_showCopyReport) {
      Settings.instance().setShowCopyReport(adapter.toggleView(v));
      settingsChanged = true;
    }

    if (drawerChanged) {
      DrawerController drawer = UI.getDrawer(context);
      if (drawer != null) {
        drawer.rebuildMenu();
      }
    }

    if (settingsChanged && navigationController != null) {
      NavigationStack stack = navigationController.getStack();
      ViewController<?> existing = stack.findLastById(R.id.controller_settings);
      if (existing != null && existing.getWrapUnchecked() != null) {
        int index = stack.indexOf(existing);
        if (index != -1 && index != stack.getCurrentIndex()) {
          stack.replace(index, new SettingsController(context, tdlib));
        }
      }
    }
  }
}
