/*
 * This file is a part of Pulsargram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thunderdog.challegram.BuildConfig;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.component.user.RemoveHelper;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.Menu;
import org.thunderdog.challegram.navigation.SettingsWrapBuilder;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.FilterRule;
import org.thunderdog.challegram.util.StringList;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import me.vkryl.core.StringUtils;
import me.vkryl.core.collection.IntList;
import me.vkryl.core.lambda.Destroyable;

public class SettingsMessageFilterController extends RecyclerViewController<Void> implements View.OnClickListener, View.OnLongClickListener, Menu {

  private SettingsAdapter adapter;
  private ArrayList<FilterRule> rules;

  public SettingsMessageFilterController(Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId() {
    return R.id.controller_messageFilter;
  }

  @Override
  public CharSequence getName() {
    return Lang.getString(R.string.FilterMessages);
  }

  @Override
  protected int getMenuId() {
    return R.id.menu_filterMessages;
  }

  @Override
  public void fillMenuItems(int id, HeaderView header, LinearLayout menu) {
    if (id == R.id.menu_filterMessages) {
      header.addButton(menu, R.id.btn_patternInfo, R.drawable.baseline_info_24, getHeaderIconColorId(), this, Screen.dp(49f));
      header.addButton(menu, R.id.btn_filterMenu, R.drawable.baseline_more_vert_24, getHeaderIconColorId(), this, Screen.dp(49f));
    }
  }

  @Override
  public void onMenuItemPressed(int id, View view) {
    if (id == R.id.btn_filterMenu) {
      showFilterMenu(view);
    } else if (id == R.id.btn_patternInfo) {
      showPatternSyntaxInfo();
    }
  }

  @Override
  protected void onCreateView(Context context, CustomRecyclerView recyclerView) {
    // Load rules from storage
    FilterRule[] savedRules = Settings.instance().getWordFilterRules();
    rules = savedRules != null ? new ArrayList<>(Arrays.asList(savedRules)) : new ArrayList<>();

    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting(ListItem item, SettingView view, boolean isUpdate) {
        final int itemId = item.getId();
        if (itemId == R.id.btn_filterEnabled) {
          view.getToggler().setRadioEnabled(Settings.instance().isWordFilterEnabled(), isUpdate);
        } else if (itemId == R.id.btn_filterPattern) {
          int position = item.getIntValue();
          if (position >= 0 && position < rules.size()) {
            FilterRule rule = rules.get(position);
            view.setName(rule.getPattern());
            view.setData(getActionName(rule.getAction()));
          }
        }
      }
    };

    buildCells();
    
    // Attach swipe-to-delete helper
    RemoveHelper.attach(recyclerView, new RemoveHelper.Callback() {
      @Override
      public boolean canRemove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, int position) {
        ListItem item = adapter.getItems().get(position);
        return item != null && item.getId() == R.id.btn_filterPattern;
      }

      @Override
      public void onRemove(RecyclerView.ViewHolder viewHolder) {
        int position = viewHolder.getAdapterPosition();
        ListItem item = adapter.getItems().get(position);
        if (item != null && item.getId() == R.id.btn_filterPattern) {
          int ruleIndex = item.getIntValue();
          if (ruleIndex >= 0 && ruleIndex < rules.size()) {
            removeRule(ruleIndex);
          }
        }
      }
    });
    
    recyclerView.setAdapter(adapter);
  }

  private void buildCells() {
    ArrayList<ListItem> items = new ArrayList<>();
    
    // Filter enable toggle
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_filterEnabled, 0, R.string.FilterEnabled));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.FilterMessagesDesc, false));

    // Rules section
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.FilterPatterns));
    
    if (rules.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.FilterPatternsEmptyDesc, false));
    } else {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      for (int i = 0; i < rules.size(); i++) {
        if (i > 0) {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        }
        // Rule item: tap to change action, long-press to edit pattern
        items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_filterPattern, 0, 0)
          .setIntValue(i));
      }
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    }
    
    // Add pattern button at the bottom
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_addFilterPattern, R.drawable.baseline_add_24, R.string.AddFilterPattern));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    adapter.setItems(items, false);
  }

  private String getActionName(@FilterRule.Action int action) {
    switch (action) {
      case FilterRule.ACTION_HIDE:
        return Lang.getString(R.string.FilterModeHide);
      case FilterRule.ACTION_SPOILER:
        return Lang.getString(R.string.FilterModeSpoiler);
      case FilterRule.ACTION_HIGHLIGHT:
        return Lang.getString(R.string.FilterModeHighlight);
      default:
        return "";
    }
  }

  private void showPatternSyntaxInfo() {
    openAlert(R.string.PatternSyntax, Lang.getString(R.string.PatternSyntaxHelp));
  }

  private void showFilterMenu(View view) {
    IntList ids = new IntList(2);
    StringList strings = new StringList(2);
    IntList icons = new IntList(2);
    
    ids.append(R.id.btn_importFilter);
    strings.append(R.string.ImportFilter);
    icons.append(R.drawable.baseline_file_download_24);
    
    ids.append(R.id.btn_exportFilter);
    strings.append(R.string.ExportFilter);
    icons.append(R.drawable.baseline_share_24);
    
    showOptions(null, ids.get(), strings.get(), null, icons.get(), (itemView, id) -> {
      if (id == R.id.btn_importFilter) {
        importFilters();
        return true;
      } else if (id == R.id.btn_exportFilter) {
        exportFilters();
        return true;
      }
      return false;
    });
  }

  private void importFilters() {
    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
    intent.setType("*/*");
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    
    context().putActivityResultHandler(Intents.ACTIVITY_RESULT_IMPORT_FILTER, (requestCode, resultCode, data) -> {
      if (resultCode == Activity.RESULT_OK && data != null) {
        Uri uri = data.getData();
        if (uri != null) {
          importFiltersFromUri(uri);
        }
      }
    });
    
    context().startActivityForResult(intent, Intents.ACTIVITY_RESULT_IMPORT_FILTER);
  }

  private void importFiltersFromUri(Uri uri) {
    try {
      InputStream inputStream = context().getContentResolver().openInputStream(uri);
      if (inputStream == null) {
        UI.showToast(R.string.ImportFilterError, Toast.LENGTH_SHORT);
        return;
      }

      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      StringBuilder jsonBuilder = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        jsonBuilder.append(line);
      }
      reader.close();
      inputStream.close();

      JSONObject json = new JSONObject(jsonBuilder.toString());
      
      // Validate version
      int version = json.optInt("version", 1);
      if (version != 1) {
        UI.showToast(R.string.ImportFilterInvalidFile, Toast.LENGTH_SHORT);
        return;
      }

      // Parse filters
      JSONArray filtersArray = json.getJSONArray("filters");
      ArrayList<FilterRule> importedRules = new ArrayList<>();
      int validFilterCount = 0;
      int duplicateCount = 0;
      
      for (int i = 0; i < filtersArray.length(); i++) {
        JSONObject filterObj = filtersArray.getJSONObject(i);
        String pattern = filterObj.getString("pattern");
        int action = filterObj.getInt("action");
        
        // Validate action
        if (action != FilterRule.ACTION_HIDE && 
            action != FilterRule.ACTION_SPOILER && 
            action != FilterRule.ACTION_HIGHLIGHT) {
          continue;
        }
        
        // Validate pattern
        if (StringUtils.isEmpty(pattern) || !isValidPattern(pattern)) {
          continue;
        }
        
        validFilterCount++;
        
        // Check for duplicates in existing rules
        boolean isDuplicate = false;
        for (FilterRule existingRule : rules) {
          if (existingRule.getPattern().equals(pattern)) {
            isDuplicate = true;
            duplicateCount++;
            break;
          }
        }
        
        if (!isDuplicate) {
          importedRules.add(new FilterRule(pattern, action));
        }
      }

      if (importedRules.isEmpty()) {
        // Check if all filters were duplicates vs no valid filters
        if (validFilterCount > 0 && duplicateCount == validFilterCount) {
          UI.showToast(R.string.ImportFilterAllDuplicates, Toast.LENGTH_SHORT);
        } else {
          UI.showToast(R.string.ImportFilterError, Toast.LENGTH_SHORT);
        }
        return;
      }

      // Add imported rules
      rules.addAll(importedRules);
      saveRules();
      buildCells();
      
      UI.showToast(Lang.getString(R.string.ImportFilterSuccess, importedRules.size()), Toast.LENGTH_SHORT);
      
    } catch (Exception e) {
      e.printStackTrace();
      UI.showToast(R.string.ImportFilterInvalidFile, Toast.LENGTH_SHORT);
    }
  }

  private void exportFilters() {
    if (rules.isEmpty()) {
      UI.showToast(R.string.NoFiltersToExport, Toast.LENGTH_SHORT);
      return;
    }

    try {
      // Create JSON structure
      JSONObject json = new JSONObject();
      json.put("version", 1);
      json.put("app", BuildConfig.PROJECT_NAME);
      json.put("exportDate", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date()));
      
      JSONArray filtersArray = new JSONArray();
      for (FilterRule rule : rules) {
        JSONObject filterObj = new JSONObject();
        filterObj.put("pattern", rule.getPattern());
        filterObj.put("action", rule.getAction());
        filtersArray.put(filterObj);
      }
      json.put("filters", filtersArray);

      // Write to file
      String filename = "client_filters_" +
        new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".json";
      File cacheDir = context().getCacheDir();
      File exportFile = new File(cacheDir, filename);
      
      FileOutputStream fos = new FileOutputStream(exportFile);
      fos.write(json.toString(2).getBytes());
      fos.close();

      // Share file
      Uri fileUri = FileProvider.getUriForFile(
        context(),
        context().getPackageName() + ".provider",
        exportFile
      );

      Intent shareIntent = new Intent(Intent.ACTION_SEND);
      shareIntent.setType("application/json");
      shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
      shareIntent.putExtra(Intent.EXTRA_SUBJECT, Lang.getString(R.string.FilterFileDescription));
      shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

      context().startActivity(Intent.createChooser(shareIntent, Lang.getString(R.string.ExportFilter)));
      
    } catch (Exception e) {
      e.printStackTrace();
      UI.showToast(R.string.ExportFilterError, Toast.LENGTH_SHORT);
    }
  }

  private void showAddRuleDialog() {
    // Show action picker first, then pattern input
    showActionPicker(FilterRule.ACTION_HIDE, (selectedAction) -> {
      showPatternInput("", selectedAction, (pattern, action) -> {
        if (StringUtils.isEmpty(pattern)) {
          UI.showToast(R.string.FilterPatternEmpty, Toast.LENGTH_SHORT);
          return false;
        }
        
        // Check for duplicate patterns
        for (FilterRule rule : rules) {
          if (rule.getPattern().equals(pattern)) {
            UI.showToast(R.string.FilterPatternDuplicate, Toast.LENGTH_SHORT);
            return false;
          }
        }
        
        // Validate pattern
        if (!isValidPattern(pattern)) {
          UI.showToast(R.string.FilterPatternInvalid, Toast.LENGTH_SHORT);
          return false;
        }
        
        addRule(new FilterRule(pattern, action));
        return true;
      });
    });
  }

  private void showEditPatternDialog(int position) {
    FilterRule currentRule = rules.get(position);
    
    showPatternInput(currentRule.getPattern(), currentRule.getAction(), (pattern, action) -> {
      if (StringUtils.isEmpty(pattern)) {
        UI.showToast(R.string.FilterPatternEmpty, Toast.LENGTH_SHORT);
        return false;
      }
      
      // Check for duplicate patterns (excluding current)
      for (int i = 0; i < rules.size(); i++) {
        if (i != position && rules.get(i).getPattern().equals(pattern)) {
          UI.showToast(R.string.FilterPatternDuplicate, Toast.LENGTH_SHORT);
          return false;
        }
      }
      
      // Validate pattern
      if (!isValidPattern(pattern)) {
        UI.showToast(R.string.FilterPatternInvalid, Toast.LENGTH_SHORT);
        return false;
      }
      
      // Update rule with new pattern, keep existing action
      rules.set(position, new FilterRule(pattern, currentRule.getAction()));
      saveRules();
      
      // Update UI - just refresh the changed item
      for (int i = 0; i < adapter.getItems().size(); i++) {
        ListItem item = adapter.getItems().get(i);
        if (item.getId() == R.id.btn_filterPattern && item.getIntValue() == position) {
          adapter.notifyItemChanged(i);
          break;
        }
      }
      
      return true;
    });
  }

  private void showEditActionDialog(int position) {
    FilterRule currentRule = rules.get(position);
    
    showActionPicker(currentRule.getAction(), (selectedAction) -> {
      // Update rule with new action, keep existing pattern
      rules.set(position, new FilterRule(currentRule.getPattern(), selectedAction));
      saveRules();
      
      // Update UI - just refresh the changed item
      for (int i = 0; i < adapter.getItems().size(); i++) {
        ListItem item = adapter.getItems().get(i);
        if (item.getId() == R.id.btn_filterPattern && item.getIntValue() == position) {
          adapter.notifyItemChanged(i);
          break;
        }
      }
    });
  }

  private void showActionPicker(@FilterRule.Action int currentAction, ActionPickerCallback callback) {
    showSettings(new SettingsWrapBuilder(R.id.btn_filterMode)
      .setRawItems(new ListItem[] {
        new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_filterModeHide, 0, R.string.FilterModeHide, R.id.btn_filterMode, currentAction == FilterRule.ACTION_HIDE),
        new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_filterModeSpoiler, 0, R.string.FilterModeSpoiler, R.id.btn_filterMode, currentAction == FilterRule.ACTION_SPOILER),
        new ListItem(ListItem.TYPE_RADIO_OPTION, R.id.btn_filterModeHighlight, 0, R.string.FilterModeHighlight, R.id.btn_filterMode, currentAction == FilterRule.ACTION_HIGHLIGHT)
      })
      .setIntDelegate((id, result) -> {
        int mode = result.get(R.id.btn_filterMode);
        @FilterRule.Action int action;
        if (mode == R.id.btn_filterModeHide) {
          action = FilterRule.ACTION_HIDE;
        } else if (mode == R.id.btn_filterModeSpoiler) {
          action = FilterRule.ACTION_SPOILER;
        } else {
          action = FilterRule.ACTION_HIGHLIGHT;
        }
        callback.onActionSelected(action);
      })
    );
  }

  private void showPatternInput(String currentPattern, @FilterRule.Action int action, PatternInputCallback callback) {
    String title = StringUtils.isEmpty(currentPattern) ? 
      Lang.getString(R.string.AddFilterPattern) : 
      Lang.getString(R.string.EditFilterPattern);
    
    openInputAlert(
      title,
      Lang.getString(R.string.FilterPatternHint),
      R.string.Save,
      R.string.Cancel,
      currentPattern,
      (inputView, result) -> {
        String pattern = result.toString().trim();
        return callback.onPatternEntered(pattern, action);
      },
      true
    );
  }

  private boolean isValidPattern(String pattern) {
    // Basic validation - pattern should not be only wildcards (*)
    if (pattern.matches("^\\*+$")) {
      return false;
    }
    // Pattern should have at least one non-wildcard character
    return pattern.replaceAll("\\*", "").length() > 0;
  }

  private int getRulesSectionStartIndex() {
    // Find where rules section starts (after header)
    List<ListItem> items = adapter.getItems();
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).getViewType() == ListItem.TYPE_HEADER && 
          items.get(i).getStringResource() == R.string.FilterPatterns) {
        return i + 1;
      }
    }
    return -1;
  }

  private void addRule(FilterRule rule) {
    rules.add(rule);
    saveRules();
    
    if (rules.size() == 1) {
      // First rule - replace empty description with rule list
      int startIndex = getRulesSectionStartIndex();
      if (startIndex != -1) {
        List<ListItem> items = adapter.getItems();
        // Remove empty description
        items.remove(startIndex);
        // Add shadow top, rule item, shadow bottom
        items.add(startIndex, new ListItem(ListItem.TYPE_SHADOW_TOP));
        items.add(startIndex + 1, new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_filterPattern, 0, 0).setIntValue(0));
        items.add(startIndex + 2, new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
        adapter.notifyItemRemoved(startIndex);
        adapter.notifyItemRangeInserted(startIndex, 3);
      }
    } else {
      // Add to existing list
      int startIndex = getRulesSectionStartIndex();
      if (startIndex != -1) {
        List<ListItem> items = adapter.getItems();
        // Find position before shadow bottom
        int insertPos = startIndex;
        while (insertPos < items.size() && items.get(insertPos).getViewType() != ListItem.TYPE_SHADOW_BOTTOM) {
          insertPos++;
        }
        // Insert separator and new rule before shadow bottom
        items.add(insertPos, new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        items.add(insertPos + 1, new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_filterPattern, 0, 0).setIntValue(rules.size() - 1));
        adapter.notifyItemRangeInserted(insertPos, 2);
      }
    }
  }

  private void removeRule(int position) {
    if (position < 0 || position >= rules.size()) {
      return;
    }
    
    rules.remove(position);
    saveRules();
    
    if (rules.isEmpty()) {
      // Last rule removed - replace list with empty description
      int startIndex = getRulesSectionStartIndex();
      if (startIndex != -1) {
        List<ListItem> items = adapter.getItems();
        // Remove shadow top, all rules with separators, shadow bottom
        int endIndex = startIndex;
        while (endIndex < items.size() && items.get(endIndex).getViewType() != ListItem.TYPE_SHADOW_BOTTOM) {
          endIndex++;
        }
        endIndex++; // Include shadow bottom
        int count = endIndex - startIndex;
        for (int i = 0; i < count; i++) {
          items.remove(startIndex);
        }
        // Add empty description
        items.add(startIndex, new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.FilterPatternsEmptyDesc, false));
        adapter.notifyItemRangeRemoved(startIndex, count);
        adapter.notifyItemInserted(startIndex);
      }
    } else {
      // Remove from existing list
      List<ListItem> items = adapter.getItems();
      // Find the rule item
      int itemIndex = -1;
      for (int i = 0; i < items.size(); i++) {
        ListItem item = items.get(i);
        if (item.getId() == R.id.btn_filterPattern && item.getIntValue() == position) {
          itemIndex = i;
          break;
        }
      }
      
      if (itemIndex != -1) {
        // Determine if we need to remove a separator
        boolean removeSeparatorBefore = itemIndex > 0 && 
          items.get(itemIndex - 1).getViewType() == ListItem.TYPE_SEPARATOR_FULL;
        boolean removeSeparatorAfter = itemIndex < items.size() - 1 && 
          items.get(itemIndex + 1).getViewType() == ListItem.TYPE_SEPARATOR_FULL;
        
        int removeStart = itemIndex;
        int removeCount = 1;
        
        if (removeSeparatorBefore) {
          removeStart = itemIndex - 1;
          removeCount = 2;
        } else if (removeSeparatorAfter) {
          removeCount = 2;
        }
        
        // Remove items
        for (int i = 0; i < removeCount; i++) {
          items.remove(removeStart);
        }
        adapter.notifyItemRangeRemoved(removeStart, removeCount);
        
        // Update indices for remaining rules
        for (int i = 0; i < items.size(); i++) {
          ListItem item = items.get(i);
          if (item.getId() == R.id.btn_filterPattern) {
            int oldIndex = item.getIntValue();
            if (oldIndex > position) {
              item.setIntValue(oldIndex - 1);
            }
          }
        }
      }
    }
  }

  private void saveRules() {
    Settings.instance().setWordFilterRules(rules.toArray(new FilterRule[0]));
    // Notify that filter rules changed so chat list can refresh
    Settings.instance().notifyMessageFilterChanged();
  }

  @Override
  public void onClick(View v) {
    final int viewId = v.getId();
    if (viewId == R.id.btn_filterEnabled) {
      boolean enabled = !Settings.instance().isWordFilterEnabled();
      Settings.instance().setWordFilterEnabled(enabled);
      adapter.updateValuedSettingById(R.id.btn_filterEnabled);
      // Notify that filter enabled state changed so chat list can refresh
      Settings.instance().notifyMessageFilterChanged();
    } else if (viewId == R.id.btn_addFilterPattern) {
      showAddRuleDialog();
    } else if (viewId == R.id.btn_filterPattern) {
      // Tap - show options menu with edit pattern and change action
      ListItem item = (ListItem) v.getTag();
      if (item != null) {
        int position = item.getIntValue();
        if (position >= 0 && position < rules.size()) {
          showRuleOptionsMenu(v, position);
        }
      }
    }
  }

  @Override
  public boolean onLongClick(View v) {
    // Long press also shows options menu
    final int viewId = v.getId();
    if (viewId == R.id.btn_filterPattern) {
      ListItem item = (ListItem) v.getTag();
      if (item != null) {
        int position = item.getIntValue();
        if (position >= 0 && position < rules.size()) {
          showRuleOptionsMenu(v, position);
          return true;
        }
      }
    }
    return false;
  }

  private void showRuleOptionsMenu(View view, int position) {
    IntList ids = new IntList(3);
    StringList strings = new StringList(3);
    IntList icons = new IntList(3);
    
    ids.append(R.id.btn_editFilterPattern);
    strings.append(R.string.EditFilterPattern);
    icons.append(R.drawable.baseline_edit_24);
    
    ids.append(R.id.btn_editFilterAction);
    strings.append(R.string.EditFilterAction);
    icons.append(R.drawable.baseline_palette_24);
    
    ids.append(R.id.btn_deleteFilter);
    strings.append(R.string.Delete);
    icons.append(R.drawable.baseline_delete_24);
    
    showOptions(null, ids.get(), strings.get(), null, icons.get(), (itemView, id) -> {
      if (id == R.id.btn_editFilterPattern) {
        showEditPatternDialog(position);
        return true;
      } else if (id == R.id.btn_editFilterAction) {
        showEditActionDialog(position);
        return true;
      } else if (id == R.id.btn_deleteFilter) {
        removeRule(position);
        return true;
      }
      return false;
    });
  }

  private interface ActionPickerCallback {
    void onActionSelected(@FilterRule.Action int action);
  }

  private interface PatternInputCallback {
    boolean onPatternEntered(String pattern, @FilterRule.Action int action);
  }
}
