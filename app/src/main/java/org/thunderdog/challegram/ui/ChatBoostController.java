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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.DoubleTextWrapper;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibCache;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.ChatBoostHeaderView;
import org.thunderdog.challegram.widget.ChatBoostLevelView;
import org.thunderdog.challegram.widget.ChatBoostLinkView;
import org.thunderdog.challegram.widget.ChatBoostOverviewView;
import org.thunderdog.challegram.widget.ChatBoostSlotView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import me.vkryl.android.widget.FrameLayoutFix;
import tgx.td.ChatId;

public class ChatBoostController extends
    BottomSheetViewController.BottomSheetBaseRecyclerViewController<ChatBoostController.Args>
    implements View.OnClickListener, TdlibCache.SupergroupDataChangeListener {
  public static class Args {
    public final long chatId;

    public Args (long chatId) {
      this.chatId = chatId;
    }
  }

  public ChatBoostController (Context context, Tdlib tdlib) {
    super(context, tdlib);
    sheet = null;
  }

  public ChatBoostController (ChatBoostSheetController sheet) {
    super(sheet.context(), sheet.tdlib());
    this.sheet = sheet;
  }

  private static final int CELL_HEADER = 0, CELL_LEVEL = 1, CELL_SLOT = 2, CELL_LINK = 3,
    CELL_OVERVIEW = 4;
  private final ChatBoostSheetController sheet;
  private final HashMap<String, Integer> measuredHeights = new HashMap<>();
  private FrameLayoutFix bottomBar;
  private TextView boostButton;
  private SettingsAdapter adapter;
  private TdApi.ChatBoostStatus status;
  private TdApi.ChatBoostSlot[] slots;
  private final Set<Integer> selectedSlots = new HashSet<>();
  private final TreeMap<Integer, TdApi.ChatBoostLevelFeatures> features = new TreeMap<>();
  private final Set<Integer> requestedLevels = new HashSet<>();
  private final List<TdApi.ChatBoost> boosts = new ArrayList<>();
  private String nextOffset = "";
  private boolean listLoaded, listLoading, listFailed, onlyGiftCodes;
  private int listGeneration, pendingLoads;
  private boolean loadFailed, slotsFailed, applying, focusedOnce;
  private boolean expandedLevels, featuresLoading, featuresFailed;
  private final Runnable updateSlots = () -> {
    refresh();
    buildCells();
  };

  private long chatId () {
    return getArgumentsStrict().chatId;
  }

  private boolean canViewBoosters () {
    return chatId() != 0 && tdlib.isAdminOrOwner(chatId());
  }

  @Override
  public int getId () {
    return R.id.controller_chatBoost;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(chatId() != 0 ? R.string.ChatBoosts : R.string.MyChatBoosts);
  }

  @Override
  protected int getRecyclerBackground () {
    return ColorId.filling;
  }

  @Override
  protected boolean needContentBackground () {
    return sheet == null;
  }

  @Override
  public boolean supportsBottomInset () {
    return true;
  }

  @Override
  public boolean needsTempUpdates () {
    return sheet != null || super.needsTempUpdates();
  }

  void onSheetShown () {
    buildCells();
  }

  @Override
  protected boolean needRecyclerBottomInset () {
    return sheet == null;
  }

  @Override
  protected void onBottomInsetChanged (int extraBottomInset, int extraBottomInsetWithoutIme,
                                       boolean isImeInset) {
    super.onBottomInsetChanged(extraBottomInset, extraBottomInsetWithoutIme, isImeInset);
    updateBottomBar();
  }

  @Override
  protected View onCreateView (Context context) {
    View view = super.onCreateView(context);
    if (sheet != null) {
      bottomBar = new FrameLayoutFix(context);
      ViewSupport.setThemedBackground(bottomBar, ColorId.filling, this);
      boostButton = new TextView(context);
      boostButton.setTextSize(16f);
      boostButton.setTypeface(Fonts.getRobotoMedium());
      boostButton.setTextColor(Theme.getColor(ColorId.fillingPositiveContent));
      addThemeTextColorListener(boostButton, ColorId.fillingPositiveContent);
      boostButton.setGravity(Gravity.CENTER);
      boostButton.setSingleLine();
      boostButton.setEllipsize(TextUtils.TruncateAt.END);
      boostButton.setPadding(Screen.dp(16f), 0, Screen.dp(16f), 0);
      boostButton.setId(R.id.btn_applyChatBoost);
      boostButton.setOnClickListener(this);
      RippleSupport.setSimpleWhiteBackground(boostButton, ColorId.fillingPositive, 6f, this);
      bottomBar.addView(boostButton, FrameLayoutFix.newParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      ((FrameLayout) view).addView(bottomBar, FrameLayoutFix.newParams(
        ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(76f), Gravity.BOTTOM));
      updateBottomBar();
      updateBoostButton();
    }
    return view;
  }

  private void updateBottomBar () {
    if (bottomBar == null)
      return;
    int height = Math.max(Screen.dp(76f), Screen.sp(16f) + Screen.dp(60f)) +
      extraBottomInsetWithoutIme;
    bottomBar.setPadding(Screen.dp(20f), Screen.dp(12f), Screen.dp(20f),
      Screen.dp(16f) + extraBottomInsetWithoutIme);
    Views.setLayoutHeight(bottomBar, height);
    Views.setBottomMargin(getRecyclerView(), height);
  }

  @Override
  public int getItemsHeight (RecyclerView parent) {
    return adapter != null ? adapter.measureScrollTop(adapter.getItemCount()) : 0;
  }

  private int getItemHeight (ListItem item) {
    switch (item.getViewType()) {
      case ListItem.TYPE_DESCRIPTION:
      case ListItem.TYPE_DESCRIPTION_SMALL:
      case ListItem.TYPE_DESCRIPTION_CENTERED:
        return item.getHeight() > 0 ? item.getHeight() : Screen.dp(44f);
      case ListItem.TYPE_PROGRESS:
        return Screen.dp(80f);
    }
    return item.getViewType() <= ListItem.TYPE_CUSTOM ?
      Math.max(Screen.dp(44f), item.getHeight()) : SettingHolder.measureHeightForType(item);
  }

  private void trackHeight (View view) {
    view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
      ListItem item = (ListItem) v.getTag();
      if (item != null && b - t > 0 && item.getHeight() != b - t) {
        item.setHeight(b - t);
        measuredHeights.put(heightKey(item), b - t);
        getRecyclerView().post(() -> {
          if (!isDestroyed()) {
            getRecyclerView().invalidateItemDecorations();
          }
        });
      }
    });
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    if (chatId() != 0) {
      tdlib.cache().subscribeToSupergroupUpdates(ChatId.toSupergroupId(chatId()), this);
    }
    adapter = new SettingsAdapter(this) {
      @Override
      public int measureScrollTop (int position) {
        int height = 0;
        for (int i = 0; i < position && i < getItemCount(); i++) {
          height += getItemHeight(getItems().get(i));
        }
        return height;
      }

      @Override
      protected View createModifiedView (ViewGroup parent, int viewType, View view) {
        switch (viewType) {
          case ListItem.TYPE_DESCRIPTION:
          case ListItem.TYPE_DESCRIPTION_SMALL:
          case ListItem.TYPE_DESCRIPTION_CENTERED:
            trackHeight(view);
            break;
          case ListItem.TYPE_PROGRESS:
            Views.setLayoutHeight(view, Screen.dp(80f));
            break;
        }
        return null;
      }

      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        view.setData(item.getStringValue());
        boolean enabled = !item.getBoolValue();
        view.setIgnoreEnabled(!enabled);
        view.setEnabled(enabled);
      }

      @Override
      protected SettingHolder initCustom (ViewGroup parent, int customViewType) {
        View view;
        switch (customViewType) {
          case CELL_HEADER:
            view = new ChatBoostHeaderView(ChatBoostController.this,
              sheet != null ? () -> sheet.hidePopupWindow(true) : null);
            break;
          case CELL_LEVEL:
            view = new ChatBoostLevelView(ChatBoostController.this);
            break;
          case CELL_SLOT:
            view = new ChatBoostSlotView(ChatBoostController.this);
            view.setOnClickListener(ChatBoostController.this);
            break;
          case CELL_LINK:
            view = new ChatBoostLinkView(ChatBoostController.this, ChatBoostController.this);
            break;
          case CELL_OVERVIEW:
            view = new ChatBoostOverviewView(ChatBoostController.this);
            break;
          default:
            throw new IllegalArgumentException();
        }
        trackHeight(view);
        addThemeInvalidateListener(view);
        return new SettingHolder(view);
      }

      @Override
      protected void modifyCustom (SettingHolder holder, int position, ListItem item,
                                   int customViewType, View view, boolean isUpdate) {
        switch (customViewType) {
          case CELL_HEADER: {
            ChatBoostHeaderView header = (ChatBoostHeaderView) view;
            if (chatId() != 0) {
              header.setChat(tdlib, chatId(), status);
              if (status == null) {
                header.setMessage(Lang.getString(loadFailed ?
                  R.string.ChatBoostLoadFailed : R.string.ChatBoostLoading));
              }
            } else {
              header.setPersonal(activeSlotCount(), unusedSlotCount());
            }
            break;
          }
          case CELL_LEVEL:
            ((ChatBoostLevelView) view).setLevel(
              (TdApi.ChatBoostLevelFeatures) item.getData(), item.getBoolValue());
            break;
          case CELL_SLOT: {
            TdApi.ChatBoostSlot slot = (TdApi.ChatBoostSlot) item.getData();
            ((ChatBoostSlotView) view).setSlot(tdlib, slot, chatId(),
              selectedSlots.contains(slot.slotId), isUsable(slot), applying || pendingLoads != 0);
            break;
          }
          case CELL_LINK:
            ((ChatBoostLinkView) view).setLink(status.boostUrl);
            break;
          case CELL_OVERVIEW:
            ((ChatBoostOverviewView) view).setStatus(status);
            break;
        }
      }
    };
    adapter.setNoEmptyProgress();
    recyclerView.setItemAnimator(null);
    recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    recyclerView.setAdapter(adapter);
    if (chatId() != 0) {
      tdlib.cache().supergroupFull(ChatId.toSupergroupId(chatId()),
        fullInfo -> runOnUiThreadOptional(this::buildCells));
    }
    refresh();
  }

  @Override
  public void onFocus () {
    super.onFocus();
    if (focusedOnce) {
      refresh();
    }
    focusedOnce = true;
    buildCells();
  }

  @Override
  public void onBlur () {
    super.onBlur();
    removeCallbacks(updateSlots);
  }

  @Override
  public void destroy () {
    removeCallbacks(updateSlots);
    if (chatId() != 0) {
      tdlib.cache().unsubscribeFromSupergroupUpdates(ChatId.toSupergroupId(chatId()), this);
    }
    super.destroy();
  }

  @Override
  public void onSupergroupUpdated (TdApi.Supergroup supergroup) {
    runOnUiThreadOptional(() -> {
      refresh();
      buildCells();
    });
  }

  @Override
  public void onSupergroupFullUpdated (long supergroupId, TdApi.SupergroupFullInfo fullInfo) {
    runOnUiThreadOptional(() -> {
      refresh();
      buildCells();
    });
  }

  private void refresh () {
    if (adapter == null || pendingLoads != 0 || applying)
      return;
    pendingLoads = chatId() != 0 ? 2 : 1;
    loadFailed = slotsFailed = false;
    if (chatId() != 0) {
      if (features.isEmpty() || featuresFailed) {
        loadFeatures();
      }
      tdlib.send(new TdApi.GetChatBoostStatus(chatId()), (result, error) ->
        runOnUiThreadOptional(() -> {
          if (error != null) {
            loadFailed = true;
            UI.showError(error);
          } else {
            status = result;
            loadLevelFeatures();
            if (canViewBoosters() && !listLoaded && !listLoading) {
              loadBoosters();
            }
          }
          pendingLoads--;
          buildCells();
        })
      );
    }
    tdlib.send(new TdApi.GetAvailableChatBoostSlots(), (result, error) ->
      runOnUiThreadOptional(() -> {
        if (error != null) {
          slotsFailed = true;
          UI.showError(error);
        } else {
          boolean firstLoad = slots == null;
          slots = result.slots;
          selectedSlots.removeIf(id -> findUsableSlot(id) == null);
          if (firstLoad && chatId() != 0) {
            for (TdApi.ChatBoostSlot slot : slots) {
              if (slot.currentlyBoostedChatId == 0 && isUsable(slot)) {
                selectedSlots.add(slot.slotId);
                break;
              }
            }
          }
        }
        pendingLoads--;
        buildCells();
      })
    );
    buildCells();
  }

  private void loadFeatures () {
    if (featuresLoading || chatId() == 0)
      return;
    featuresLoading = true;
    featuresFailed = false;
    tdlib.send(new TdApi.GetChatBoostFeatures(tdlib.isChannel(chatId())), (result, error) ->
      runOnUiThreadOptional(() -> {
        featuresLoading = false;
        featuresFailed = error != null;
        if (error == null) {
          for (TdApi.ChatBoostLevelFeatures level : result.features) {
            features.put(level.level, level);
          }
          loadLevelFeatures();
        }
        buildCells();
      })
    );
  }

  private void loadLevelFeatures () {
    if (status == null)
      return;
    for (int level = status.level; level <= status.level + 1; level++) {
      if ((level > status.level && status.nextLevelBoostCount == 0) ||
          features.containsKey(level) || !requestedLevels.add(level))
        continue;
      final int requestedLevel = level;
      tdlib.send(new TdApi.GetChatBoostLevelFeatures(tdlib.isChannel(chatId()), level),
        (result, error) -> runOnUiThreadOptional(() -> {
          if (error == null) {
            features.put(result.level, result);
          } else {
            requestedLevels.remove(requestedLevel);
            featuresFailed = true;
          }
          buildCells();
        })
      );
    }
  }

  private boolean isUsable (TdApi.ChatBoostSlot slot) {
    long now = tdlib.currentTime(TimeUnit.SECONDS);
    return slot.expirationDate > now && slot.cooldownUntilDate <= now &&
      slot.currentlyBoostedChatId != chatId();
  }

  private TdApi.ChatBoostSlot findUsableSlot (int slotId) {
    if (slots != null) {
      for (TdApi.ChatBoostSlot slot : slots) {
        if (slot.slotId == slotId && isUsable(slot))
          return slot;
      }
    }
    return null;
  }

  private void addSetting (List<ListItem> items, int id, int icon, CharSequence title,
                           CharSequence value, boolean disabled) {
    items.add(new ListItem(value != null ?
      ListItem.TYPE_VALUED_SETTING_COMPACT : ListItem.TYPE_SETTING, id, icon, title)
      .setStringValue(value).setBoolValue(disabled)
      .setTextColorId(value != null ? ColorId.text : ColorId.textNeutral));
  }

  private String heightKey (ListItem item) {
    return item.getViewType() + ":" + item.getLongId() + ":" + item.getString();
  }

  private ListItem addCustom (List<ListItem> items, int type, Object data, long id) {
    return addCustom(items, type, data, id, 0);
  }

  private ListItem addCustom (List<ListItem> items, int type, Object data, long id, int actionId) {
    ListItem item = new ListItem(ListItem.TYPE_CUSTOM - type, actionId)
      .setData(data).setLongId(id);
    item.setHeight(measuredHeights.getOrDefault(heightKey(item), Screen.dp(80f)));
    items.add(item);
    return item;
  }

  private int activeSlotCount () {
    int count = 0;
    if (slots != null) {
      long now = tdlib.currentTime(TimeUnit.SECONDS);
      for (TdApi.ChatBoostSlot slot : slots) {
        if (slot.expirationDate > now)
          count++;
      }
    }
    return count;
  }

  private int unusedSlotCount () {
    int count = 0;
    if (slots != null) {
      long now = tdlib.currentTime(TimeUnit.SECONDS);
      for (TdApi.ChatBoostSlot slot : slots) {
        if (slot.expirationDate > now && slot.cooldownUntilDate <= now &&
            slot.currentlyBoostedChatId == 0)
          count++;
      }
    }
    return count;
  }

  private int usableSlotCount () {
    int count = 0;
    if (slots != null) {
      for (TdApi.ChatBoostSlot slot : slots) {
        if (isUsable(slot))
          count++;
      }
    }
    return count;
  }

  private void buildCells () {
    if (adapter == null)
      return;
    selectedSlots.removeIf(id -> findUsableSlot(id) == null);
    List<ListItem> items = new ArrayList<>();
    addCustom(items, CELL_HEADER, status, 0);
    if (chatId() != 0 && status != null) {
      int highlightedLevel = status.nextLevelBoostCount == 0 ? status.level : status.level + 1;
      TdApi.ChatBoostLevelFeatures next = features.get(highlightedLevel);
      if (next != null) {
        addCustom(items, CELL_LEVEL, next, highlightedLevel)
          .setBoolValue(status.nextLevelBoostCount != 0);
      } else if (featuresFailed) {
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.ChatBoostFeaturesFailed));
      } else {
        items.add(new ListItem(ListItem.TYPE_PROGRESS));
      }
      if (!features.isEmpty()) {
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        addSetting(items, R.id.btn_chatBoostLevels, R.drawable.baseline_stars_24,
          Lang.getString(expandedLevels ?
            R.string.ChatBoostHideLevels : R.string.ChatBoostAllLevels),
          null, false);
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        if (expandedLevels) {
          for (TdApi.ChatBoostLevelFeatures level : features.values()) {
            if (level.level != highlightedLevel) {
              addCustom(items, CELL_LEVEL, level, level.level);
            }
          }
        }
      }
    }
    if ((loadFailed && status != null) || slotsFailed) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.ChatBoostLoadFailed));
    }

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.MyChatBoosts));
    if (slots != null && !slotsFailed) {
      int slotCount = 0;
      for (TdApi.ChatBoostSlot slot : slots) {
        if (slot.expirationDate <= tdlib.currentTime(TimeUnit.SECONDS))
          continue;
        if (slotCount++ > 0) {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR));
        }
        addCustom(items, CELL_SLOT, slot, slot.slotId, R.id.btn_chatBoostSlot);
      }
      if (slotCount > 0 && chatId() != 0 && usableSlotCount() > 0) {
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.ChatBoostSelectInfo));
      } else if (slotCount == 0) {
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0,
          tdlib.hasPremium() ? R.string.ChatBoostNoSlots : chatId() == 0 ?
          R.string.ChatBoostMyPremiumRequired : R.string.ChatBoostPremiumRequired));
      }
    } else if (pendingLoads != 0) {
      items.add(new ListItem(ListItem.TYPE_PROGRESS));
    }

    if (chatId() != 0 && status != null) {
      if (!status.boostUrl.isEmpty()) {
        items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.ChatBoostLink));
        addCustom(items, CELL_LINK, status.boostUrl, 0);
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.ChatBoostLinkInfo));
      }
      TdApi.SupergroupFullInfo fullInfo =
        tdlib.cache().supergroupFull(ChatId.toSupergroupId(chatId()));
      if (fullInfo != null && fullInfo.unrestrictBoostCount > 0) {
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0,
          Lang.plural(R.string.ChatBoostUnrestrictInfo, fullInfo.unrestrictBoostCount)));
      }
      if (fullInfo != null && tdlib.isSupergroup(chatId()) && tdlib.canRestrictMembers(chatId())) {
        items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.ChatBoostGroupSettings));
        addSetting(items, R.id.btn_boostRestrictionCount, R.drawable.baseline_lock_24,
          Lang.getString(R.string.ChatBoostUnrestrictSetting),
          fullInfo.unrestrictBoostCount == 0 ?
          Lang.getString(R.string.ChatBoostUnrestrictDisabled) :
          Lang.plural(R.string.ChatBoostCount, fullInfo.unrestrictBoostCount), applying);
      }
      if (canViewBoosters()) {
        addBoosters(items);
      }
    }
    if (loadFailed || slotsFailed || featuresFailed) {
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      addSetting(items, R.id.btn_refreshChatBoost, R.drawable.baseline_sync_24,
        Lang.getString(R.string.ChatBoostRetry), null, pendingLoads != 0 || applying);
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
    }
    for (ListItem item : items) {
      if (item.getViewType() == ListItem.TYPE_DESCRIPTION) {
        item.setHeight(measuredHeights.getOrDefault(heightKey(item), Screen.dp(44f)));
      }
    }
    // Keep the header attached so loading updates preserve the sheet's decorated scroll offset.
    adapter.replaceItems(items);
    updateBoostButton();
    removeCallbacks(updateSlots);
    if (slots != null && (sheet != null ? sheet.isShowing() : isFocused()) && !isDestroyed()) {
      long now = tdlib.currentTime(TimeUnit.SECONDS);
      long nextUpdate = Long.MAX_VALUE;
      for (TdApi.ChatBoostSlot slot : slots) {
        if (slot.cooldownUntilDate > now) {
          nextUpdate = Math.min(nextUpdate, slot.cooldownUntilDate);
        }
        if (slot.expirationDate > now) {
          nextUpdate = Math.min(nextUpdate, slot.expirationDate);
        }
      }
      if (nextUpdate != Long.MAX_VALUE) {
        UI.post(updateSlots, TimeUnit.SECONDS.toMillis(nextUpdate - now) + 100);
      }
    }
  }

  private void updateBoostButton () {
    if (boostButton == null)
      return;
    boolean enabled = true;
    CharSequence text;
    if (applying || pendingLoads != 0) {
      text = Lang.getString(applying ? R.string.ChatBoostApplying : R.string.ChatBoostLoading);
      enabled = false;
    } else if (loadFailed || slotsFailed || status == null) {
      text = Lang.getString(R.string.ChatBoostRetry);
    } else if (status.nextLevelBoostCount == 0) {
      text = Lang.getString(R.string.Done);
    } else if (!selectedSlots.isEmpty()) {
      boolean moving = false;
      for (int id : selectedSlots) {
        TdApi.ChatBoostSlot slot = findUsableSlot(id);
        moving |= slot != null && slot.currentlyBoostedChatId != 0;
      }
      text = moving ? Lang.plural(R.string.ChatBoostMoveCount, selectedSlots.size()) :
        selectedSlots.size() > 1 ? Lang.plural(R.string.ChatBoostApplyCount, selectedSlots.size()) :
        Lang.getString(tdlib.isChannel(chatId()) ?
          R.string.ChatBoostChannel : R.string.ChatBoostGroup);
    } else if (usableSlotCount() > 0) {
      text = Lang.getString(R.string.ChatBoostChoose);
    } else if (status.appliedSlotIds.length > 0) {
      text = Lang.getString(R.string.ChatBoostDone);
    } else if (activeSlotCount() > 0) {
      text = Lang.getString(R.string.MyChatBoosts);
    } else {
      text = Lang.getString(R.string.ChatBoostShareLink);
      enabled = !status.boostUrl.isEmpty();
    }
    boostButton.setText(text);
    boostButton.setEnabled(enabled);
    boostButton.setAlpha(enabled ? 1f : .6f);
  }

  private void onBoostButtonClick () {
    if (applying || pendingLoads != 0)
      return;
    if (loadFailed || slotsFailed || status == null) {
      refresh();
    } else if (status.nextLevelBoostCount == 0 ||
        (selectedSlots.isEmpty() && usableSlotCount() == 0 && status.appliedSlotIds.length > 0)) {
      if (sheet != null) {
        sheet.hidePopupWindow(true);
      }
    } else if (!selectedSlots.isEmpty()) {
      confirmBoost();
    } else if (activeSlotCount() > 0) {
      int position = adapter.indexOfViewById(R.id.btn_chatBoostSlot);
      if (position >= 0) {
        getRecyclerView().smoothScrollToPosition(position);
      }
    } else if (!status.boostUrl.isEmpty()) {
      shareBoostLink();
    }
  }

  private void shareBoostLink () {
    if (status == null || status.boostUrl.isEmpty())
      return;
    String text = Lang.getString(R.string.ChatBoostShare,
      tdlib.chatTitle(chatId()), status.boostUrl);
    ShareController controller = new ShareController(context(), tdlib);
    controller.setArguments(new ShareController.Args(text).setShare(text, null));
    controller.show();
  }

  private void addBoosters (List<ListItem> items) {
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.ChatBoostOverview));
    addCustom(items, CELL_OVERVIEW, status, 0);
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.ChatBoosters));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    addSetting(items, R.id.btn_chatBoostGiftCodes, 0,
      Lang.getString(R.string.ChatBoostFilter),
      Lang.getString(onlyGiftCodes ? R.string.ChatBoostGiftCodes : R.string.ChatBoostAll),
      listLoading || applying);
    for (TdApi.ChatBoost boost : boosts) {
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      long userId = boosterUserId(boost.source);
      String source = Lang.getString(boost.source instanceof TdApi.ChatBoostSourcePremium ?
        R.string.ChatBoostSourcePremium : boost.source instanceof TdApi.ChatBoostSourceGiftCode ?
        R.string.ChatBoostSourceGift : R.string.ChatBoostSourceGiveaway);
      CharSequence subtitle = Lang.getString(R.string.ChatBoostEntry,
        source, Lang.plural(R.string.ChatBoostCount, boost.count),
        Lang.getDate(boost.expirationDate, TimeUnit.SECONDS));
      if (userId != 0) {
        DoubleTextWrapper wrapper = new DoubleTextWrapper(tdlib, userId, false);
        wrapper.setSubtitle(subtitle);
        items.add(new ListItem(ListItem.TYPE_CHAT_SMALL, R.id.btn_chatBooster)
          .setData(wrapper).setLongId(userId));
      } else {
        addSetting(items, 0, R.drawable.dotvhs_baseline_gift_24,
          Lang.getString(R.string.ChatBoostSourceGiveaway), subtitle, true);
      }
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    if (listLoading) {
      items.add(new ListItem(ListItem.TYPE_PROGRESS));
    } else if (listFailed || !listLoaded || !nextOffset.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      addSetting(items, R.id.btn_moreChatBoosters, 0,
        Lang.getString(listFailed ? R.string.ChatBoostRetry : R.string.ChatBoostLoadMore),
        null, applying);
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    } else if (boosts.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.ChatBoostersEmpty));
    }
  }

  private static long boosterUserId (TdApi.ChatBoostSource source) {
    if (source instanceof TdApi.ChatBoostSourcePremium)
      return ((TdApi.ChatBoostSourcePremium) source).userId;
    if (source instanceof TdApi.ChatBoostSourceGiftCode)
      return ((TdApi.ChatBoostSourceGiftCode) source).userId;
    if (source instanceof TdApi.ChatBoostSourceGiveaway)
      return ((TdApi.ChatBoostSourceGiveaway) source).userId;
    return 0;
  }

  private void resetBoosters () {
    listGeneration++;
    boosts.clear();
    nextOffset = "";
    listLoaded = listLoading = listFailed = false;
  }

  private void loadBoosters () {
    if (listLoading || !canViewBoosters() || (listLoaded && nextOffset.isEmpty()))
      return;
    final int generation = listGeneration;
    final String offset = nextOffset;
    listLoading = true;
    listFailed = false;
    tdlib.send(new TdApi.GetChatBoosts(chatId(), onlyGiftCodes, offset, 50),
      (result, error) -> runOnUiThreadOptional(() -> {
        if (generation != listGeneration)
          return;
        listLoading = false;
        if (error != null) {
          listFailed = true;
          UI.showError(error);
        } else {
          Set<String> ids = new HashSet<>();
          for (TdApi.ChatBoost boost : boosts) {
            ids.add(boost.id);
          }
          for (TdApi.ChatBoost boost : result.boosts) {
            if (ids.add(boost.id)) {
              boosts.add(boost);
            }
          }
          nextOffset = result.nextOffset.equals(offset) ? "" : result.nextOffset;
          listLoaded = true;
        }
        buildCells();
      })
    );
    buildCells();
  }

  private void confirmBoost () {
    if (applying || pendingLoads != 0 || slotsFailed || status == null ||
        status.nextLevelBoostCount == 0)
      return;
    List<String> previousChats = new ArrayList<>();
    List<Integer> ids = new ArrayList<>();
    for (int id : selectedSlots) {
      TdApi.ChatBoostSlot slot = findUsableSlot(id);
      if (slot == null)
        continue;
      ids.add(id);
      if (slot.currentlyBoostedChatId != 0) {
        String title = tdlib.chatTitle(slot.currentlyBoostedChatId);
        if (!previousChats.contains(title)) {
          previousChats.add(title);
        }
      }
    }
    if (ids.isEmpty()) {
      refresh();
      return;
    }
    int[] slotIds = new int[ids.size()];
    long[] previousChatIds = new long[ids.size()];
    for (int i = 0; i < ids.size(); i++) {
      slotIds[i] = ids.get(i);
      previousChatIds[i] = findUsableSlot(slotIds[i]).currentlyBoostedChatId;
    }
    if (previousChats.isEmpty()) {
      applyBoost(slotIds, previousChatIds);
    } else {
      showConfirm(Lang.getString(R.string.ChatBoostReassignConfirm,
        android.text.TextUtils.join(", ", previousChats), tdlib.chatTitle(chatId())),
        Lang.getString(R.string.ChatBoostReassign), R.drawable.baseline_flash_on_24,
        OptionColor.BLUE, () -> applyBoost(slotIds, previousChatIds));
    }
  }

  private void applyBoost (int[] slotIds, long[] expectedChatIds) {
    if (isDestroyed() || applying || pendingLoads != 0)
      return;
    for (int i = 0; i < slotIds.length; i++) {
      TdApi.ChatBoostSlot slot = findUsableSlot(slotIds[i]);
      if (slot == null || slot.currentlyBoostedChatId != expectedChatIds[i]) {
        refresh();
        return;
      }
    }
    Set<Long> previousChatIds = new HashSet<>();
    for (int id : slotIds) {
      long previousChatId = findUsableSlot(id).currentlyBoostedChatId;
      if (previousChatId != 0) {
        previousChatIds.add(previousChatId);
      }
    }
    applying = true;
    buildCells();
    tdlib.send(new TdApi.BoostChat(chatId(), slotIds), (result, error) ->
      runOnUiThreadOptional(() -> {
        applying = false;
        if (error != null) {
          UI.showError(error);
        } else {
          selectedSlots.clear();
          slots = result.slots;
          UI.showToast(Lang.plural(R.string.ChatBoostSuccess, slotIds.length), Toast.LENGTH_SHORT);
          // Refresh full info so group permission and slow mode exemptions update too.
          tdlib.send(new TdApi.GetSupergroupFullInfo(ChatId.toSupergroupId(chatId())),
            (fullInfo, fullInfoError) -> { });
          for (long previousChatId : previousChatIds) {
            tdlib.send(new TdApi.GetSupergroupFullInfo(ChatId.toSupergroupId(previousChatId)),
              (fullInfo, fullInfoError) -> { });
          }
        }
        resetBoosters();
        refresh();
      })
    );
  }

  private void editUnrestrictBoostCount () {
    if (applying || !tdlib.isSupergroup(chatId()) || !tdlib.canRestrictMembers(chatId()))
      return;
    int[] ids = new int[10];
    String[] labels = new String[10];
    for (int count = 0; count <= 8; count++) {
      ids[count] = View.generateViewId();
      labels[count] = count == 0 ? Lang.getString(R.string.ChatBoostUnrestrictDisabled) :
        Lang.plural(R.string.ChatBoostCount, count);
    }
    ids[9] = R.id.btn_cancel;
    labels[9] = Lang.getString(R.string.Cancel);
    showOptions(Lang.getString(R.string.ChatBoostUnrestrictSettingInfo), ids, labels, null, null,
      (view, id) -> {
        if (isDestroyed() || applying || !tdlib.canRestrictMembers(chatId()))
          return true;
        for (int count = 0; count <= 8; count++) {
          if (id == ids[count]) {
            applying = true;
            buildCells();
            tdlib.send(new TdApi.SetSupergroupUnrestrictBoostCount(
              ChatId.toSupergroupId(chatId()), count), (result, error) ->
              runOnUiThreadOptional(() -> {
                applying = false;
                if (error != null) {
                  UI.showError(error);
                  buildCells();
                } else {
                  tdlib.send(new TdApi.GetSupergroupFullInfo(ChatId.toSupergroupId(chatId())),
                    (fullInfo, fullInfoError) -> runOnUiThreadOptional(() -> {
                      if (fullInfoError != null) {
                        UI.showError(fullInfoError);
                      }
                      refresh();
                    })
                  );
                }
              })
            );
            break;
          }
        }
        return true;
      }
    );
  }

  @Override
  public void onClick (View view) {
    int id = view.getId();
    if (id == R.id.btn_applyChatBoost) {
      onBoostButtonClick();
    } else if (id == R.id.btn_refreshChatBoost) {
      if (!applying && pendingLoads == 0) {
        resetBoosters();
        refresh();
      }
    } else if (id == R.id.btn_copyLink && status != null) {
      UI.copyText(status.boostUrl, R.string.CopiedLink);
    } else if (id == R.id.btn_share && status != null) {
      shareBoostLink();
    } else if (id == R.id.btn_chatBoostLevels) {
      expandedLevels = !expandedLevels;
      buildCells();
    } else if (id == R.id.btn_chatBoostSlot && !applying && pendingLoads == 0) {
      TdApi.ChatBoostSlot slot = (TdApi.ChatBoostSlot) ((ListItem) view.getTag()).getData();
      if (chatId() == 0 && slot.currentlyBoostedChatId != 0) {
        tdlib.ui().openChatBoost(this, slot.currentlyBoostedChatId);
      } else if (chatId() != 0 && isUsable(slot)) {
        if (!selectedSlots.remove(slot.slotId)) {
          selectedSlots.add(slot.slotId);
        }
        buildCells();
      }
    } else if (id == R.id.btn_moreChatBoosters) {
      loadBoosters();
    } else if (id == R.id.btn_chatBoostGiftCodes && !listLoading) {
      onlyGiftCodes = !onlyGiftCodes;
      resetBoosters();
      loadBoosters();
    } else if (id == R.id.btn_chatBooster) {
      long userId = ((ListItem) view.getTag()).getLongId();
      tdlib.ui().openPrivateProfile(this, userId, null);
    } else if (id == R.id.btn_boostRestrictionCount) {
      editUnrestrictBoostCount();
    }
  }
}
