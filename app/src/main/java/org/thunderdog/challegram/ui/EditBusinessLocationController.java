/*
 * This file is a part of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.attach.MediaBottomBaseController;
import org.thunderdog.challegram.component.attach.MediaBottomLocationController;
import org.thunderdog.challegram.component.attach.MediaLayout;
import org.thunderdog.challegram.component.attach.MediaLocationData;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.BusinessLocationDraft;
import org.thunderdog.challegram.loader.ImageFileMap;
import org.thunderdog.challegram.navigation.HeaderButton;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.ImageReceiverView;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;
import org.thunderdog.challegram.widget.PopupLayout;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.core.lambda.Destroyable;

public final class EditBusinessLocationController extends RecyclerViewController<Void>
    implements View.OnClickListener, SettingsAdapter.TextChangeListener {
  private SettingsAdapter adapter;
  private BusinessLocationDraft draft;
  private HeaderButton doneButton;
  private boolean saving;
  private boolean hadLocation;
  private boolean choosingLocation;

  public EditBusinessLocationController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  public static void open (ViewController<?> parent) {
    if (parent.tdlib().hasPremium()) {
      parent.navigateTo(new EditBusinessLocationController(parent.context(), parent.tdlib()));
    } else {
      parent.navigateTo(new SettingsBusinessController(parent.context(), parent.tdlib()));
    }
  }

  @Override
  public int getId () { return R.id.controller_businessLocation; }

  @Override
  public CharSequence getName () { return Lang.getString(R.string.Location); }

  @Override
  protected int getMenuId () { return R.id.menu_done; }

  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    doneButton = header.addDoneButton(menu, this);
    updateDone();
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id == R.id.menu_btn_done) save();
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void modifyEditText (ListItem item, ViewGroup parent, MaterialEditTextGroup edit) {
        edit.getEditText().setInputType(InputType.TYPE_CLASS_TEXT |
          InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        Views.setSingleLine(edit.getEditText(), false);
        edit.getEditText().setMaxLines(5);
        edit.setMaxLength(BusinessLocationDraft.MAX_ADDRESS_LENGTH, false);
      }

      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        if (item.getId() == R.id.btn_businessLocationMap) {
          view.getToggler().setRadioEnabled(draft != null && draft.location() != null, isUpdate);
        }
      }

      @Override
      protected SettingHolder initCustom (ViewGroup parent, int customViewType) {
        FrameLayout frame = new FrameLayout(context);
        frame.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
          Screen.dp(240)));
        ImageReceiverView preview = new ImageReceiverView(context) {
          @Override
          protected void onAttachedToWindow () {
            super.onAttachedToWindow();
            attach();
          }

          @Override
          protected void onDetachedFromWindow () {
            detach();
            super.onDetachedFromWindow();
          }
        };
        preview.setBackgroundColor(Theme.placeholderColor());
        addThemeBackgroundColorListener(preview, ColorId.placeholder);
        addDestroyListener(preview);
        frame.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView pin = new ImageView(context);
        pin.setImageResource(R.drawable.ic_map_pin_44);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Screen.dp(44), Screen.dp(44),
          android.view.Gravity.CENTER);
        params.bottomMargin = Screen.dp(44);
        frame.addView(pin, params);
        frame.setOnClickListener(v -> chooseLocation());
        return new SettingHolder(frame);
      }

      @Override
      protected void modifyCustom (SettingHolder holder, int position, ListItem item,
                                   int customViewType, View view, boolean isUpdate) {
        ImageReceiverView preview = (ImageReceiverView) ((FrameLayout) view).getChildAt(0);
        TdApi.Location location = (TdApi.Location) item.getData();
        preview.getReceiver().requestFile(new ImageFileMap(location.latitude, location.longitude,
          15, Screen.currentWidth(), Screen.dp(240)));
      }
    };
    adapter.setTextChangeListener(this);
    recyclerView.setAdapter(adapter);
    TdApi.UserFullInfo cached = tdlib.cache().userFull(tdlib.myUserId());
    if (cached != null) {
      setUserFull(cached);
    } else {
      adapter.setItems(new ListItem[] {new ListItem(ListItem.TYPE_PROGRESS)}, false);
      tdlib.send(new TdApi.GetUserFullInfo(tdlib.myUserId()), (full, error) -> tdlib.uiExecute(() -> {
        if (isDestroyed()) return;
        if (error != null) {
          UI.showError(error);
          navigateBack();
        } else {
          setUserFull(full);
        }
      }));
    }
  }

  private void setUserFull (TdApi.UserFullInfo full) {
    TdApi.BusinessLocation location = BusinessLocationDraft.fromUser(full);
    hadLocation = location != null;
    draft = new BusinessLocationDraft(location);
    buildCells();
  }

  private void buildCells () {
    ArrayList<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION_CENTERED, 0, 0, R.string.BusinessLocationInfo));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_EDITTEXT_COUNTERED, R.id.input, 0,
      R.string.BusinessLocationAddress).setStringValue(draft.address()).setInputFilters(
        new InputFilter[] {(source, start, end, dest, dstart, dend) -> {
          String text = source.subSequence(start, end).toString();
          return text.indexOf('\n') >= 0 ? text.replace("\n", "") : null;
        }}));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_businessLocationMap, 0,
      R.string.BusinessLocationMap));
    TdApi.Location location = draft.location();
    if (location != null) {
      items.add(new ListItem(ListItem.TYPE_CUSTOM, R.id.businessLocationPreview).setData(location));
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    if (hadLocation && !draft.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_delete, 0,
        R.string.BusinessLocationClear).setTextColorId(ColorId.textNegative));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    }
    adapter.setItems(items, false);
    updateDone();
  }

  @Override
  public void onTextChanged (int id, ListItem item, MaterialEditTextGroup view) {
    if (draft == null || saving) return;
    draft.setAddress(view.getText().toString());
    adapter.updateEditTextById(R.id.input, false, false);
    updateDone();
  }

  private void updateDone () {
    if (doneButton == null) return;
    boolean enabled = draft != null && draft.hasChanges() && !saving && !choosingLocation;
    doneButton.setEnabled(enabled);
    doneButton.setAlpha(enabled || saving || choosingLocation ? 1f : 0f);
    doneButton.setShowProgress(saving || choosingLocation, -1f);
  }

  @Override
  public void onClick (View view) {
    if (saving || draft == null) return;
    if (view.getId() == R.id.btn_businessLocationMap) {
      if (draft.location() != null) {
        draft.setLocation(null, null);
        buildCells();
      } else {
        chooseLocation();
      }
    } else if (view.getId() == R.id.btn_delete) {
      BusinessLocationUi.confirmRemove(this, this::navigateBack);
    }
  }

  private void save () {
    if (draft == null || saving || !draft.hasChanges()) return;
    if (!draft.isValid()) {
      adapter.updateEditTextById(R.id.input, false, true);
      return;
    }
    saving = true;
    adapter.updateLockEditTextById(R.id.input, draft.address());
    updateDone();
    tdlib.send(new TdApi.SetBusinessLocation(draft.toLocation()), (ok, error) -> tdlib.uiExecute(() -> {
      if (isDestroyed()) return;
      saving = false;
      adapter.updateLockEditTextById(R.id.input, null);
      updateDone();
      if (error != null) UI.showError(error);
      else navigateBack();
    }));
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (saving) return true;
    if (draft != null && draft.hasChanges() && !draft.isEmpty()) {
      if (commit) {
        showOptions(Lang.getString(R.string.BusinessLocationUnsavedChanges),
          new int[] {R.id.btn_done, R.id.btn_delete, R.id.btn_cancel},
          new String[] {Lang.getString(R.string.Apply), Lang.getString(R.string.DiscardChanges),
            Lang.getString(R.string.Cancel)}, null, null, (view, id) -> {
            if (id == R.id.btn_done) save();
            else if (id == R.id.btn_delete) navigateBack();
            return true;
          });
      }
      return true;
    }
    return super.performOnBackPressed(fromTop, commit);
  }

  @Override
  public boolean canSlideBackFrom (NavigationController navigation, float x, float y) {
    return !saving && (draft == null || !draft.hasChanges());
  }

  private void chooseLocation () {
    if (saving || draft == null || choosingLocation) return;
    hideSoftwareKeyboard();
    TdApi.Location initial = draft.location();
    String address = draft.address();
    if (initial != null || address.trim().isEmpty()) {
      showLocationPicker(initial);
      return;
    }
    choosingLocation = true;
    updateDone();
    Background.instance().post(() -> {
      TdApi.Location resolved = initial;
      if (resolved == null && !address.trim().isEmpty()) {
        try {
          List<Address> matches = new Geocoder(context, Lang.locale())
            .getFromLocationName(address, 1);
          if (matches != null && !matches.isEmpty()) {
            Address match = matches.get(0);
            resolved = new TdApi.Location(match.getLatitude(), match.getLongitude(), 0);
          }
        } catch (Exception ignored) { }
      }
      TdApi.Location selected = resolved;
      tdlib.uiExecute(() -> {
        choosingLocation = false;
        if (!isDestroyed()) {
          updateDone();
          showLocationPicker(selected);
        }
      });
    });
  }

  private void showLocationPicker (TdApi.Location initial) {
    Destroyable[] destroy = new Destroyable[1];
    MediaLayout layout = new MediaLayout(this) {
      @Override
      public MediaBottomBaseController<?> createControllerForIndex (int index) {
        MediaBottomLocationController controller = new MediaBottomLocationController(this);
        controller.setLocationPicker(initial, R.string.BusinessLocationSelect);
        return controller;
      }

      @Override
      public void onPopupDismiss (PopupLayout popup) {
        removeDestroyListener(destroy[0]);
        super.onPopupDismiss(popup);
      }

      @Override
      public void sendLocation (double latitude, double longitude, double accuracy,
                                int heading, int livePeriod) {
        selectLocation(new TdApi.Location(latitude, longitude, accuracy), null);
        hide(false);
      }

      @Override
      public void sendVenue (MediaLocationData place) {
        selectLocation(place.convertToInputMessage().venue.location, place.getAddress());
        hide(false);
      }
    };
    destroy[0] = () -> {
      if (layout.isVisible()) layout.hide(false);
      else layout.performDestroy();
    };
    addDestroyListener(destroy[0]);
    layout.init(MediaLayout.MODE_LOCATION, null);
    layout.preload(() -> { if (!isDestroyed()) layout.show(); }, 300);
  }

  private int selectionId;

  private void selectLocation (TdApi.Location location, String address) {
    if (isDestroyed()) return;
    draft.setLocation(location, address);
    buildCells();
    final int requestId = ++selectionId;
    if (address != null) return;
    Background.instance().post(() -> {
      String resolved = null;
      try {
        List<Address> matches = new Geocoder(context, Lang.locale())
          .getFromLocation(location.latitude, location.longitude, 1);
        if (matches != null && !matches.isEmpty()) resolved = matches.get(0).getAddressLine(0);
      } catch (Exception ignored) { }
      String result = resolved;
      tdlib.uiExecute(() -> {
        if (!isDestroyed() && !saving && requestId == selectionId &&
            draft.location() != null && draft.location().latitude == location.latitude &&
            draft.location().longitude == location.longitude) {
          draft.setLocation(location, result);
          buildCells();
        }
      });
    });
  }

}
