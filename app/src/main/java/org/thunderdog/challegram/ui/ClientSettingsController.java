package org.thunderdog.challegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;

import org.thunderdog.challegram.unsorted.Settings;

public class ClientSettingsController extends RecyclerViewController<Void> implements View.OnClickListener {

  public ClientSettingsController(Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId() {
    return R.id.controller_clientSettings;
  }

  @Override
  public CharSequence getName() {
    return Lang.getString(R.string.ClientSettings);
  }

  private SettingsAdapter adapter;

  @Override
  protected void onCreateView(Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting(ListItem item, SettingView view, boolean isUpdate) {
        if (ClientSettingsExtension.bindSetting(item, view, isUpdate)) {
          return;
        }
        final int itemId = item.getId();
        if (itemId == R.id.btn_sendAsButton) {
          view.getToggler().setRadioEnabled(Settings.instance().getSendAsButton(), isUpdate);
        } else if (itemId == R.id.btn_cameraButtonToggle) {
          view.getToggler().setRadioEnabled(Settings.instance().getCameraButton(), isUpdate);
        } else if (itemId == R.id.btn_voiceVideoMessageButtonToggle) {
          view.getToggler().setRadioEnabled(Settings.instance().getVoiceVideoMessageButton(), isUpdate);
        } else if (itemId == R.id.btn_discussButtonToggle) {
          view.getToggler().setRadioEnabled(Settings.instance().getDiscussButton(), isUpdate);
        } else if (itemId == R.id.btn_muteButtonToggle) {
          view.getToggler().setRadioEnabled(Settings.instance().getMuteButton(), isUpdate);
        } else if (itemId == R.id.btn_recentActionsButtonToggle) {
          view.getToggler().setRadioEnabled(Settings.instance().getRecentActionsButton(), isUpdate);
        } else if (itemId == R.id.btn_showPeerIds) {
          view.getToggler().setRadioEnabled(Settings.instance().showPeerIds(), isUpdate);
        } else if (itemId == R.id.btn_pipButtonToggle) {
          view.getToggler().setRadioEnabled(Settings.instance().getPipButton(), isUpdate);
        } else if (itemId == R.id.btn_copyPhotoToggle) {
          view.getToggler().setRadioEnabled(Settings.instance().getCopyPhoto(), isUpdate);
        } else if (itemId == R.id.btn_saveCameraPhotosToGallery) {
          view.getToggler().setRadioEnabled(!Settings.instance().getNewSetting(Settings.SETTING_FLAG_DISABLE_CAMERA_PHOTO_GALLERY_SAVE), isUpdate);
        } else if (itemId == R.id.btn_swipeFolderFilter) {
          view.getToggler().setRadioEnabled(Settings.instance().getSwipeFolderFilter(), isUpdate);
        }
      }
    };

    ArrayList<ListItem> items = new ArrayList<>();
    
    // Interface & Appearance
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.InterfaceAndAppearance));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_menuVisibility, R.drawable.baseline_visibility_24, R.string.MenuVisibility));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_swipeFolderFilter, R.drawable.baseline_layers_24, R.string.SwipeFolderFilter));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    // Chat & Messages
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.ChatAndMessages));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_notes, R.drawable.baseline_edit_24, R.string.Notes));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_filterMessages, R.drawable.baseline_filter_list_24, R.string.FilterMessages));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_sendAsButton, R.drawable.baseline_send_24, R.string.SendAsButton));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_cameraButtonToggle, R.drawable.baseline_camera_alt_24, R.string.CameraButtonToggle));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_voiceVideoMessageButtonToggle, R.drawable.baseline_mic_24, R.string.VoiceVideoMessageButtonToggle));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_discussButtonToggle, R.drawable.baseline_forum_24, R.string.DiscussButtonToggle));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_muteButtonToggle, R.drawable.baseline_notifications_off_24, R.string.MuteButtonToggle));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_recentActionsButtonToggle, R.drawable.baseline_history_24, R.string.RecentActionsButtonToggle));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    // Media & Content
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.MediaAndContent));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_copyPhotoToggle, R.drawable.baseline_content_copy_24, R.string.CopyPhotoToggle));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_saveCameraPhotosToGallery, R.drawable.baseline_camera_alt_24, R.string.SaveCameraPhotosToGallery));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_pipButtonToggle, R.drawable.deproko_baseline_outinline_24, R.string.PipButtonToggle));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    // Developer & Experimental
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.DeveloperAndExperimental));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_showPeerIds, R.drawable.baseline_identifier_24, R.string.Experiment_PeerIds));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    ClientSettingsExtension.appendSettings(items);

    adapter.setItems(items, false);
    recyclerView.setAdapter(adapter);
  }

  @Override
  public void onClick(View v) {
    if (ClientSettingsExtension.handleClick(v, adapter)) {
      return;
    }
    final int viewId = v.getId();
    if (viewId == R.id.btn_menuVisibility) {
      navigateTo(new SettingsMenuVisibilityController(context, tdlib));
    } else if (viewId == R.id.btn_notes) {
      navigateTo(new SettingsNotesController(context, tdlib));
    } else if (viewId == R.id.btn_filterMessages) {
      navigateTo(new SettingsMessageFilterController(context, tdlib));
    } else if (viewId == R.id.btn_showPeerIds) {
      boolean show = adapter.toggleView(v);
      Settings.instance().setExperimentEnabled(Settings.EXPERIMENT_FLAG_SHOW_PEER_IDS, show);
    } else if (viewId == R.id.btn_sendAsButton) {
      boolean show = adapter.toggleView(v);
      Settings.instance().setSendAsButton(show);
    } else if (viewId == R.id.btn_cameraButtonToggle) {
      boolean show = adapter.toggleView(v);
      Settings.instance().setCameraButton(show);
    } else if (viewId == R.id.btn_voiceVideoMessageButtonToggle) {
      boolean show = adapter.toggleView(v);
      Settings.instance().setVoiceVideoMessageButton(show);
    } else if (viewId == R.id.btn_discussButtonToggle) {
      boolean show = adapter.toggleView(v);
      Settings.instance().setDiscussButton(show);
    } else if (viewId == R.id.btn_muteButtonToggle) {
      boolean show = adapter.toggleView(v);
      Settings.instance().setMuteButton(show);
    } else if (viewId == R.id.btn_recentActionsButtonToggle) {
      boolean show = adapter.toggleView(v);
      Settings.instance().setRecentActionsButton(show);
    } else if (viewId == R.id.btn_pipButtonToggle) {
      boolean show = adapter.toggleView(v);
      Settings.instance().setPipButton(show);
    } else if (viewId == R.id.btn_copyPhotoToggle) {
      boolean enabled = adapter.toggleView(v);
      Settings.instance().setCopyPhoto(enabled);
    } else if (viewId == R.id.btn_saveCameraPhotosToGallery) {
      boolean enabled = adapter.toggleView(v);
      Settings.instance().setNewSetting(Settings.SETTING_FLAG_DISABLE_CAMERA_PHOTO_GALLERY_SAVE, !enabled);
    } else if (viewId == R.id.btn_swipeFolderFilter) {
      boolean enabled = adapter.toggleView(v);
      Settings.instance().setSwipeFolderFilter(enabled);
    }
  }
}
